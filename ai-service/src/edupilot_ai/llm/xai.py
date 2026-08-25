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
    LlmFileAttachment,
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
XAI_RESPONSES_URL = f"{XAI_BASE_URL}/responses"
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
    tool: str = "chat.completions",
) -> None:
    logger.log(
        logging.INFO if status == "SUCCESS" else logging.WARNING,
        "xAI chat completion finished",
        extra={
            "agent": "Grok",
            "tool": tool,
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


class _ResponsesTokenDetails(_XaiModel):
    reasoning_tokens: int | None = Field(default=None, ge=0)


class _ResponsesUsage(_XaiModel):
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    output_tokens_details: _ResponsesTokenDetails


class _ResponsesContent(_XaiModel):
    type: str
    text: str | None = None


class _ResponsesOutputItem(_XaiModel):
    type: str
    role: str | None = None
    content: list[_ResponsesContent] = Field(default_factory=list)


class _ResponsesResponse(_XaiModel):
    model: str
    status: str
    output: list[_ResponsesOutputItem]
    usage: _ResponsesUsage | None = None


def _responses_output_text(response: _ResponsesResponse) -> str:
    texts = [
        content.text
        for item in response.output
        if item.type == "message" and item.role == "assistant"
        for content in item.content
        if content.type == "output_text" and content.text is not None
    ]
    if response.status != "completed" or len(texts) != 1:
        raise ValueError("Responses output must contain one completed assistant text")
    return texts[0]


def _responses_usage(response: _ResponsesResponse) -> LlmUsage:
    if response.status != "completed" or response.usage is None:
        raise ValueError("Responses output is not complete or has no usage")
    return LlmUsage(
        model=response.model,
        input_tokens=response.usage.input_tokens,
        output_tokens=response.usage.output_tokens,
        reasoning_tokens=response.usage.output_tokens_details.reasoning_tokens,
    )


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
        attachments: Sequence[LlmFileAttachment] = (),
    ) -> LlmCompletion[ModelT]:
        if attachments:
            return await self._complete_json_with_files(
                messages=messages,
                response_model=response_model,
                profile=profile,
                timeout_seconds=timeout_seconds,
                attachments=attachments,
            )
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
        attachments: Sequence[LlmFileAttachment] = (),
    ) -> AsyncIterator[LlmTextStreamItem]:
        if attachments:
            async for item in self._complete_text_stream_with_files(
                messages=messages,
                profile=profile,
                timeout_seconds=timeout_seconds,
                attachments=attachments,
            ):
                yield item
            return
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

    async def _complete_json_with_files(
        self,
        *,
        messages: Sequence[LlmMessage],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment],
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
                tool="responses",
            )
            raise LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True)

        payload = self._responses_payload(
            messages=messages,
            profile=profile,
            attachments=attachments,
        )
        payload["text"] = {
            "format": {
                "type": "json_schema",
                "name": response_model.__name__,
                "schema": response_model.model_json_schema(by_alias=True),
                "strict": True,
            }
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
                    tool="responses",
                )
                raise LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True)

            response_started = False
            try:
                async with asyncio.timeout_at(deadline):
                    async with self._client.stream(
                        "POST",
                        XAI_RESPONSES_URL,
                        headers=self._headers(),
                        json=payload,
                        timeout=self._timeout(remaining_seconds),
                    ) as attempt_response:
                        # A file response may already be running document search once
                        # headers exist, so replay is unsafe past this boundary.
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
                    tool="responses",
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
                    tool="responses",
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
                tool="responses",
            )
            raise LlmBridgeError(
                category=ErrorCategory.INTERNAL,
                retryable=response.status_code == 429 or response.status_code >= 500,
            )

        try:
            provider_response = _ResponsesResponse.model_validate(response.json())
            content = _responses_output_text(provider_response)
            usage = _responses_usage(provider_response)
        except (json.JSONDecodeError, ValidationError, ValueError) as exception:
            _log_call(
                model=profile.model,
                started_at=started_at,
                status="FAILED",
                attempt=successful_attempt,
                error_code=ErrorCategory.SCHEMA.value,
                failure_kind="schema",
                tool="responses",
            )
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
            ) from exception

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
                tool="responses",
            )
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
                usage=usage,
            ) from exception

        self._warn_model_mismatch(expected=profile.model, actual=provider_response.model)
        _log_call(
            model=provider_response.model,
            started_at=started_at,
            status="SUCCESS",
            attempt=successful_attempt,
            tool="responses",
        )
        return LlmCompletion(output=output, usage=usage)

    async def _complete_text_stream_with_files(
        self,
        *,
        messages: Sequence[LlmMessage],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment],
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
                tool="responses",
            )
            raise LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True)

        payload = self._responses_payload(
            messages=messages,
            profile=profile,
            attachments=attachments,
        )
        payload["stream"] = True
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
                    tool="responses",
                )
                raise LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True)

            provider_response: _ResponsesResponse | None = None
            text_parts: list[str] = []
            done = False
            response_body_started = False
            response: httpx.Response | None = None
            try:
                async with AsyncExitStack() as response_stack:
                    async with asyncio.timeout_at(deadline):
                        response = await response_stack.enter_async_context(
                            self._client.stream(
                                "POST",
                                XAI_RESPONSES_URL,
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
                            tool="responses",
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
                            if loop.time() >= deadline:
                                raise TimeoutError
                            async with asyncio.timeout_at(deadline):
                                data = await anext(sse_events)
                            if loop.time() >= deadline:
                                raise TimeoutError
                        except StopAsyncIteration:
                            break
                        if data == "[DONE]":
                            done = True
                            break
                        try:
                            event = json.loads(data)
                            if not isinstance(event, dict):
                                raise ValueError("Responses event must be an object")
                            event_type = event.get("type")
                            if not isinstance(event_type, str):
                                raise ValueError("Responses event type is missing")
                            if event_type == "response.output_text.delta":
                                delta = event.get("delta")
                                if not isinstance(delta, str):
                                    raise ValueError("Responses text delta is invalid")
                                if delta:
                                    text_parts.append(delta)
                                    yield LlmTextDelta(text=delta)
                            elif event_type == "response.completed":
                                provider_response = _ResponsesResponse.model_validate(
                                    event.get("response")
                                )
                                # Responses streams terminate with this event. Unlike
                                # Chat Completions, a trailing ``[DONE]`` may be absent;
                                # keep draining when present so the connection is reusable.
                                done = True
                            elif event_type in {
                                "error",
                                "response.failed",
                                "response.incomplete",
                            }:
                                _log_call(
                                    model=profile.model,
                                    started_at=started_at,
                                    status="FAILED",
                                    attempt=attempt,
                                    error_code=ErrorCategory.INTERNAL.value,
                                    failure_kind="provider",
                                    tool="responses",
                                )
                                raise LlmBridgeError(
                                    category=ErrorCategory.INTERNAL,
                                    retryable=True,
                                )
                            # Reasoning, file-search, and item lifecycle events are
                            # provider internals and never learner-facing deltas.
                        except (json.JSONDecodeError, ValidationError, ValueError) as exception:
                            _log_call(
                                model=profile.model,
                                started_at=started_at,
                                status="FAILED",
                                attempt=attempt,
                                error_code=ErrorCategory.SCHEMA.value,
                                failure_kind="schema",
                                tool="responses",
                            )
                            raise LlmBridgeError(
                                category=ErrorCategory.SCHEMA,
                                retryable=False,
                            ) from exception
            except (httpx.TimeoutException, TimeoutError) as exception:
                _log_call(
                    model=(provider_response.model if provider_response else profile.model),
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.TIMEOUT.value,
                    failure_kind="timeout",
                    exception_type=type(exception).__name__,
                    tool="responses",
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
                    model=(provider_response.model if provider_response else profile.model),
                    started_at=started_at,
                    status="RETRYING" if retry else "FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.INTERNAL.value,
                    failure_kind="network",
                    exception_type=type(exception).__name__,
                    tool="responses",
                )
                if retry:
                    continue
                raise LlmBridgeError(
                    category=ErrorCategory.INTERNAL,
                    retryable=True,
                ) from exception

            if not done:
                _log_call(
                    model=(provider_response.model if provider_response else profile.model),
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.INTERNAL.value,
                    failure_kind="provider",
                    tool="responses",
                )
                raise LlmBridgeError(category=ErrorCategory.INTERNAL, retryable=True)
            try:
                if provider_response is None:
                    raise ValueError("Responses stream has no terminal response")
                if "".join(text_parts) != _responses_output_text(provider_response):
                    raise ValueError("Responses deltas do not match terminal output text")
                usage = _responses_usage(provider_response)
            except ValueError as exception:
                _log_call(
                    model=(provider_response.model if provider_response else profile.model),
                    started_at=started_at,
                    status="FAILED",
                    attempt=attempt,
                    error_code=ErrorCategory.SCHEMA.value,
                    failure_kind="schema",
                    tool="responses",
                )
                raise LlmBridgeError(
                    category=ErrorCategory.SCHEMA,
                    retryable=False,
                ) from exception

            self._warn_model_mismatch(expected=profile.model, actual=provider_response.model)
            _log_call(
                model=provider_response.model,
                started_at=started_at,
                status="SUCCESS",
                attempt=attempt,
                tool="responses",
            )
            yield LlmTextStreamCompleted(usage=usage)
            return

    @staticmethod
    def _responses_input(
        *,
        messages: Sequence[LlmMessage],
        attachments: Sequence[LlmFileAttachment],
    ) -> list[dict[str, Any]]:
        inputs = [dict(message) for message in messages]
        user_index = next(
            (
                index
                for index in range(len(inputs) - 1, -1, -1)
                if inputs[index].get("role") == "user"
            ),
            None,
        )
        if user_index is None:
            raise ValueError("File attachments require a user message")
        content = inputs[user_index].get("content")
        if not isinstance(content, str):
            raise ValueError("File attachments currently require text-only user content")
        file_parts: list[dict[str, str]] = []
        for attachment in attachments:
            file_id = attachment.file_id.strip()
            if not file_id:
                raise ValueError("File attachment ID must not be blank")
            file_parts.append({"type": "input_file", "file_id": file_id})
        inputs[user_index]["content"] = [
            {"type": "input_text", "text": content},
            *file_parts,
        ]
        return inputs

    def _responses_payload(
        self,
        *,
        messages: Sequence[LlmMessage],
        profile: AgentLlmProfile,
        attachments: Sequence[LlmFileAttachment],
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": profile.model,
            "input": self._responses_input(
                messages=messages,
                attachments=attachments,
            ),
            "reasoning_effort": profile.reasoning_effort.value,
            "max_output_tokens": profile.max_tokens,
            "store": False,
        }
        if profile.temperature is not None:
            payload["temperature"] = profile.temperature
        return payload

    @staticmethod
    def _warn_model_mismatch(*, expected: str, actual: str) -> None:
        if actual != expected:
            logger.warning(
                "xAI response model mismatch: expected=%s actual=%s",
                expected,
                actual,
            )

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
