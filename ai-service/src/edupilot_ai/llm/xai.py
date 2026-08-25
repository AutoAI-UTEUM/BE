"""xAI OpenAI-compatible structured-output adapter."""

import asyncio
import codecs
import json
import logging
from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import AsyncExitStack
from time import perf_counter
from typing import Any

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmCompletion,
    LlmMessage,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
    ModelT,
)
from edupilot_ai.settings import AgentLlmProfile

XAI_BASE_URL = "https://api.x.ai/v1"
XAI_CHAT_COMPLETIONS_URL = f"{XAI_BASE_URL}/chat/completions"
_MAX_NETWORK_ATTEMPTS = 3
_RETRYABLE_NETWORK_ERRORS = (httpx.NetworkError, httpx.RemoteProtocolError)

logger = logging.getLogger(__name__)


def _log_call(
    *,
    model: str,
    started_at: float,
    status: str,
    attempt: int,
    error_code: str | None = None,
    failure_kind: str | None = None,
    exception_type: str | None = None,
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
            "attempt": attempt,
            "exceptionType": exception_type,
        },
    )


def _can_retry_network_error(
    exception: httpx.RequestError,
    *,
    attempt: int,
    no_retry_boundary_crossed: bool,
) -> bool:
    """Retry only selected transport failures before caller-visible output starts."""
    return (
        isinstance(exception, _RETRYABLE_NETWORK_ERRORS)
        and not no_retry_boundary_crossed
        and attempt < _MAX_NETWORK_ATTEMPTS
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


def _consume_sse_line(line: str, data_lines: list[str]) -> str | None:
    if not line:
        if data_lines:
            data = "\n".join(data_lines)
            data_lines.clear()
            return data
        return None
    if line.startswith(":"):
        return None
    if line.startswith("data:"):
        data_lines.append(line.removeprefix("data:").lstrip())
    return None


async def _sse_data(
    response: httpx.Response,
    *,
    on_body_chunk: Callable[[], None] | None = None,
) -> AsyncIterator[str]:
    """Parse SSE frames without exposing provider-specific framing upstream."""
    data_lines: list[str] = []
    text_buffer = ""
    decoder = codecs.getincrementaldecoder("utf-8")()
    async for chunk in response.aiter_bytes():
        if chunk and on_body_chunk is not None:
            on_body_chunk()
        text_buffer += decoder.decode(chunk)
        while "\n" in text_buffer:
            line, text_buffer = text_buffer.split("\n", 1)
            data = _consume_sse_line(line.removesuffix("\r"), data_lines)
            if data is not None:
                yield data
    text_buffer += decoder.decode(b"", final=True)
    if text_buffer:
        data = _consume_sse_line(text_buffer.removesuffix("\r"), data_lines)
        if data is not None:
            yield data
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
        messages: Sequence[LlmMessage],
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
                attempt=1,
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

        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_seconds
        response: httpx.Response | None = None
        successful_attempt = 1
        for attempt in range(1, _MAX_NETWORK_ATTEMPTS + 1):
            remaining_seconds = deadline - loop.time()
            if remaining_seconds <= 0:
                _log_call(
                    model=profile.model,
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.TIMEOUT.value,
                    failure_kind="timeout",
                )
                raise LlmBridgeError(
                    category=ErrorCategory.TIMEOUT,
                    retryable=True,
                )

            response_started = False
            try:
                async with asyncio.timeout_at(deadline):
                    async with self._client.stream(
                        "POST",
                        XAI_CHAT_COMPLETIONS_URL,
                        headers=self._headers(),
                        json=payload,
                        timeout=self._timeout(remaining_seconds),
                    ) as attempt_response:
                        # Structured output cannot be replayed once HTTP response
                        # headers exist, even if the body later fails while buffering.
                        response_started = True
                        await attempt_response.aread()
                        response = attempt_response
            except (httpx.TimeoutException, TimeoutError) as exception:
                _log_call(
                    model=profile.model,
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.TIMEOUT.value,
                    failure_kind="timeout",
                    exception_type=type(exception).__name__,
                )
                raise LlmBridgeError(
                    category=ErrorCategory.TIMEOUT,
                    retryable=True,
                ) from exception
            except httpx.RequestError as exception:
                retry = _can_retry_network_error(
                    exception,
                    attempt=attempt,
                    no_retry_boundary_crossed=response_started,
                )
                _log_call(
                    model=profile.model,
                    started_at=started_at,
                    status="RETRYING" if retry else "FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.INTERNAL.value,
                    failure_kind="network",
                    exception_type=type(exception).__name__,
                )
                if retry:
                    continue
                raise LlmBridgeError(
                    category=ErrorCategory.INTERNAL,
                    retryable=True,
                ) from exception
            successful_attempt = attempt
            break

        if response is None:  # pragma: no cover - loop exits only via success or error
            raise AssertionError("xAI response missing after retry loop")

        if response.is_error:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                attempt=successful_attempt,
                error_code=ErrorCategory.INTERNAL.value,
                failure_kind=("rate_limit" if response.status_code == 429 else "provider"),
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
                attempt=successful_attempt,
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
                attempt=successful_attempt,
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
            attempt=successful_attempt,
        )
        return LlmCompletion(
            output=output,
            usage=usage,
        )

    async def complete_text_stream(
        self,
        *,
        messages: Sequence[LlmMessage],
        profile: AgentLlmProfile,
        timeout_seconds: float,
    ) -> AsyncIterator[LlmTextStreamItem]:
        started_at = perf_counter()
        if timeout_seconds <= 0:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                attempt=1,
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

        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_seconds
        for attempt in range(1, _MAX_NETWORK_ATTEMPTS + 1):
            remaining_seconds = deadline - loop.time()
            if remaining_seconds <= 0:
                _log_call(
                    model=profile.model,
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.TIMEOUT.value,
                    failure_kind="timeout",
                )
                raise LlmBridgeError(
                    category=ErrorCategory.TIMEOUT,
                    retryable=True,
                )

            provider_usage: _CompletionUsage | None = None
            provider_model: str | None = None
            completed = False
            response_body_started = False
            response: httpx.Response | None = None
            try:
                async with AsyncExitStack() as response_stack:
                    async with asyncio.timeout_at(deadline):
                        response = await response_stack.enter_async_context(
                            self._client.stream(
                                "POST",
                                XAI_CHAT_COMPLETIONS_URL,
                                headers=self._headers(accept_stream=True),
                                json=payload,
                                timeout=self._timeout(remaining_seconds),
                            )
                        )
                    if response.is_error:
                        _log_call(
                            model=profile.model,
                            started_at=started_at,
                            status="FAILED",
                            attempt=attempt,
                            error_code=ErrorCategory.INTERNAL.value,
                            failure_kind=(
                                "rate_limit" if response.status_code == 429 else "provider"
                            ),
                        )
                        raise LlmBridgeError(
                            category=ErrorCategory.INTERNAL,
                            retryable=response.status_code == 429 or response.status_code >= 500,
                        )

                    def mark_response_body_started() -> None:
                        nonlocal response_body_started
                        response_body_started = True

                    sse_events = _sse_data(
                        response,
                        on_body_chunk=mark_response_body_started,
                    ).__aiter__()
                    while True:
                        try:
                            # Scope the deadline to provider I/O only. Keeping an
                            # asyncio timeout active across ``yield`` would also
                            # cancel the downstream consumer while it handles a delta.
                            if loop.time() >= deadline:
                                raise TimeoutError
                            async with asyncio.timeout_at(deadline):
                                data = await anext(sse_events)
                            if loop.time() >= deadline:
                                raise TimeoutError
                        except StopAsyncIteration:
                            break
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
                                attempt=attempt,
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
            except (httpx.TimeoutException, TimeoutError) as exception:
                _log_call(
                    model=provider_model or profile.model,
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.TIMEOUT.value,
                    failure_kind="timeout",
                    exception_type=type(exception).__name__,
                )
                raise LlmBridgeError(
                    category=ErrorCategory.TIMEOUT,
                    retryable=True,
                ) from exception
            except httpx.RequestError as exception:
                raw_body_started = response is not None and response.num_bytes_downloaded > 0
                retry = _can_retry_network_error(
                    exception,
                    attempt=attempt,
                    no_retry_boundary_crossed=(response_body_started or raw_body_started),
                )
                _log_call(
                    model=provider_model or profile.model,
                    started_at=started_at,
                    status="RETRYING" if retry else "FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.INTERNAL.value,
                    failure_kind="network",
                    exception_type=type(exception).__name__,
                )
                if retry:
                    continue
                raise LlmBridgeError(
                    category=ErrorCategory.INTERNAL,
                    retryable=True,
                ) from exception

            if not completed:
                _log_call(
                    model=provider_model or profile.model,
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
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
                    attempt=attempt,
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
                attempt=attempt,
            )
            yield LlmTextStreamCompleted(
                usage=LlmUsage(
                    model=provider_model,
                    input_tokens=provider_usage.prompt_tokens,
                    output_tokens=provider_usage.completion_tokens,
                    reasoning_tokens=(details.reasoning_tokens if details is not None else None),
                )
            )
            return

    def _base_payload(
        self,
        *,
        messages: Sequence[LlmMessage],
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
