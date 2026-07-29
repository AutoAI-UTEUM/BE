"""xAI OpenAI-compatible structured-output adapter."""

import json
import logging
from collections.abc import AsyncIterator, Mapping, Sequence
from time import perf_counter
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmCompletion,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
    ModelT,
)
from edupilot_ai.settings import AgentLlmProfile

XAI_BASE_URL = "https://api.x.ai/v1"
XAI_CHAT_COMPLETIONS_URL = f"{XAI_BASE_URL}/chat/completions"

logger = logging.getLogger(__name__)


def _log_call(
    *,
    model: str,
    started_at: float,
    status: str,
    error_code: str | None = None,
    failure_kind: str | None = None,
) -> None:
    logger.log(
        logging.INFO if status == "SUCCESS" else logging.WARNING,
        "xAI chat completion finished",
        extra={
            "agent": "Grok",
            "tool": "chat.completions",
            "model": model,
            "status": status,
            "durationMs": round((perf_counter() - started_at) * 1000, 3),
            "errorCode": error_code,
            "failureKind": failure_kind,
            "attempt": 1,
        },
    )


class _XaiModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class _CompletionTokenDetails(_XaiModel):
    reasoning_tokens: int | None = Field(default=None, ge=0)


class _CompletionUsage(_XaiModel):
    prompt_tokens: int = Field(ge=0)
    completion_tokens: int = Field(ge=0)
    completion_tokens_details: _CompletionTokenDetails | None = None


class _CompletionMessage(_XaiModel):
    content: str


class _CompletionChoice(_XaiModel):
    message: _CompletionMessage


class _CompletionResponse(_XaiModel):
    model: str
    choices: list[_CompletionChoice] = Field(min_length=1)
    usage: _CompletionUsage


class _StreamDelta(_XaiModel):
    content: str | None = None


class _StreamChoice(_XaiModel):
    delta: _StreamDelta


class _StreamChunk(_XaiModel):
    model: str
    choices: list[_StreamChoice] = Field(default_factory=list)
    usage: _CompletionUsage | None = None


async def _sse_data(response: httpx.Response) -> AsyncIterator[str]:
    """Parse SSE frames without exposing provider-specific framing upstream."""
    data_lines: list[str] = []
    async for line in response.aiter_lines():
        if not line:
            if data_lines:
                yield "\n".join(data_lines)
                data_lines.clear()
            continue
        if line.startswith(":"):
            continue
        if line.startswith("data:"):
            data_lines.append(line.removeprefix("data:").lstrip())
    if data_lines:
        yield "\n".join(data_lines)


class XaiLlmBridge:
    """Call xAI chat completions without embedding provider details in agents."""

    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        api_key: SecretStr,
    ) -> None:
        self._client = client
        self._api_key = api_key

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
        timeout_seconds: float,
    ) -> LlmCompletion[ModelT]:
        started_at = perf_counter()
        if timeout_seconds <= 0:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.TIMEOUT.value,
                failure_kind="timeout",
            )
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            )
        payload = self._base_payload(messages=messages, profile=profile)
        payload["response_format"] = {
            "type": "json_schema",
            "json_schema": {
                "name": response_model.__name__,
                "strict": True,
                "schema": response_model.model_json_schema(by_alias=True),
            },
        }

        try:
            response = await self._client.post(
                XAI_CHAT_COMPLETIONS_URL,
                headers=self._headers(),
                json=payload,
                timeout=self._timeout(timeout_seconds),
            )
        except httpx.TimeoutException as exception:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.TIMEOUT.value,
                failure_kind="timeout",
            )
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            ) from exception
        except httpx.RequestError as exception:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.INTERNAL.value,
                failure_kind="network",
            )
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=True,
            ) from exception

        if response.is_error:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.INTERNAL.value,
                failure_kind=(
                    "rate_limit" if response.status_code == 429 else "provider"
                ),
            )
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=response.status_code == 429 or response.status_code >= 500,
            )

        try:
            provider_response = _CompletionResponse.model_validate(response.json())
            content = provider_response.choices[0].message.content
        except (json.JSONDecodeError, ValidationError, IndexError) as exception:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.SCHEMA.value,
                failure_kind="schema",
            )
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
            ) from exception

        details = provider_response.usage.completion_tokens_details
        usage = LlmUsage(
            model=provider_response.model,
            input_tokens=provider_response.usage.prompt_tokens,
            output_tokens=provider_response.usage.completion_tokens,
            reasoning_tokens=details.reasoning_tokens if details is not None else None,
        )
        try:
            output = response_model.model_validate_json(content)
        except (json.JSONDecodeError, ValidationError) as exception:
            _log_call(
                model=provider_response.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.SCHEMA.value,
                failure_kind="schema",
            )
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
                usage=usage,
            ) from exception

        if provider_response.model != profile.model:
            logger.warning(
                "xAI response model mismatch: expected=%s actual=%s",
                profile.model,
                provider_response.model,
            )

        _log_call(
            model=provider_response.model,
            started_at=started_at,
            status="SUCCESS",
        )
        return LlmCompletion(
            output=output,
            usage=usage,
        )

    async def complete_text_stream(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        profile: AgentLlmProfile,
        timeout_seconds: float,
    ) -> AsyncIterator[LlmTextStreamItem]:
        started_at = perf_counter()
        if timeout_seconds <= 0:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.TIMEOUT.value,
                failure_kind="timeout",
            )
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            )
        payload = self._base_payload(messages=messages, profile=profile)
        payload["stream"] = True
        payload["stream_options"] = {"include_usage": True}

        provider_usage: _CompletionUsage | None = None
        provider_model: str | None = None
        completed = False
        try:
            async with self._client.stream(
                "POST",
                XAI_CHAT_COMPLETIONS_URL,
                headers=self._headers(accept_stream=True),
                json=payload,
                timeout=self._timeout(timeout_seconds),
            ) as response:
                if response.is_error:
                    _log_call(
                        model=profile.model,
                        started_at=started_at,
                        status="FAILED",
                        error_code=ErrorCategory.INTERNAL.value,
                        failure_kind=(
                            "rate_limit"
                            if response.status_code == 429
                            else "provider"
                        ),
                    )
                    raise LlmBridgeError(
                        category=ErrorCategory.INTERNAL,
                        retryable=response.status_code == 429
                        or response.status_code >= 500,
                    )

                async for data in _sse_data(response):
                    if data == "[DONE]":
                        completed = True
                        break
                    try:
                        chunk = _StreamChunk.model_validate_json(data)
                    except (json.JSONDecodeError, ValidationError) as exception:
                        _log_call(
                            model=provider_model or profile.model,
                            started_at=started_at,
                            status="FAILED",
                            error_code=ErrorCategory.SCHEMA.value,
                            failure_kind="schema",
                        )
                        raise LlmBridgeError(
                            category=ErrorCategory.SCHEMA,
                            retryable=False,
                        ) from exception
                    provider_model = chunk.model
                    if chunk.usage is not None:
                        provider_usage = chunk.usage
                    if chunk.choices:
                        text = chunk.choices[0].delta.content
                        if text:
                            yield LlmTextDelta(text=text)
        except httpx.TimeoutException as exception:
            _log_call(
                model=provider_model or profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.TIMEOUT.value,
                failure_kind="timeout",
            )
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            ) from exception
        except httpx.RequestError as exception:
            _log_call(
                model=provider_model or profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.INTERNAL.value,
                failure_kind="network",
            )
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=True,
            ) from exception

        if not completed:
            _log_call(
                model=provider_model or profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.INTERNAL.value,
                failure_kind="provider",
            )
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=True,
            )
        if provider_usage is None or provider_model is None:
            _log_call(
                model=provider_model or profile.model,
                started_at=started_at,
                status="FAILED",
                error_code=ErrorCategory.SCHEMA.value,
                failure_kind="schema",
            )
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
            )
        if provider_model != profile.model:
            logger.warning(
                "xAI response model mismatch: expected=%s actual=%s",
                profile.model,
                provider_model,
            )
        details = provider_usage.completion_tokens_details
        _log_call(
            model=provider_model,
            started_at=started_at,
            status="SUCCESS",
        )
        yield LlmTextStreamCompleted(
            usage=LlmUsage(
                model=provider_model,
                input_tokens=provider_usage.prompt_tokens,
                output_tokens=provider_usage.completion_tokens,
                reasoning_tokens=details.reasoning_tokens if details is not None else None,
            )
        )

    def _base_payload(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        profile: AgentLlmProfile,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": profile.model,
            "messages": [dict(message) for message in messages],
            "reasoning_effort": profile.reasoning_effort.value,
            "max_tokens": profile.max_tokens,
        }
        if profile.temperature is not None:
            payload["temperature"] = profile.temperature
        return payload

    def _headers(self, *, accept_stream: bool = False) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self._api_key.get_secret_value()}",
            "Content-Type": "application/json",
        }
        if accept_stream:
            headers["Accept"] = "text/event-stream"
        return headers

    @staticmethod
    def _timeout(timeout_seconds: float) -> httpx.Timeout:
        if timeout_seconds <= 0:
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            )
        return httpx.Timeout(timeout_seconds)
