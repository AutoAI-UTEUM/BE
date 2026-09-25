"""xAI OpenAI-compatible structured-output adapter."""

import asyncio
import codecs
import json
import logging
import re
from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import AsyncExitStack, asynccontextmanager
from dataclasses import dataclass, field, replace
from time import perf_counter
from typing import Any
from uuid import uuid4

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from edupilot_ai.core.async_iterators import closing_async_iterator, shielded_aclose
from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.core.observability import ContentTiming
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmCompletion,
    LlmFileAttachment,
    LlmMessage,
    LlmPromptCache,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
    ModelT,
)
from edupilot_ai.llm.prompt_cache import stable_prefix_messages
from edupilot_ai.settings import AgentLlmProfile

XAI_BASE_URL = "https://api.x.ai/v1"
XAI_CHAT_COMPLETIONS_URL = f"{XAI_BASE_URL}/chat/completions"
XAI_RESPONSES_URL = f"{XAI_BASE_URL}/responses"
_MAX_NETWORK_ATTEMPTS = 3
_RETRYABLE_NETWORK_ERRORS = (httpx.NetworkError, httpx.RemoteProtocolError)
# Provider-defined counters only; never copy tool names/arguments or search results.
_SERVER_SIDE_TOOL_COUNTERS = (
    "web_search_calls",
    "x_search_calls",
    "code_interpreter_calls",
    "file_search_calls",
    "mcp_calls",
    "document_search_calls",
    "image_generation_calls",
)
_MODEL_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class _CallMetrics:
    """Log-only metadata for one logical call; no prompt/answer/file identifiers."""

    timing: ContentTiming
    request_fields: dict[str, object]
    call_id: str = field(default_factory=lambda: str(uuid4()))
    usage_fields: dict[str, int | dict[str, int]] = field(default_factory=dict)
    provider_model: str | None = None
    provider_usage_final: bool = False
    provider_status_code: int | None = None
    attempt_started_at: float | None = None

    @classmethod
    def start(
        cls,
        *,
        started_at: float,
        messages: Sequence[LlmMessage],
        profile: AgentLlmProfile,
        attachments: Sequence[LlmFileAttachment],
        streaming: bool,
        response_model: str | None = None,
        cache_layout: bool = False,
        cache_routing: bool = False,
    ) -> _CallMetrics:
        text_chars = 0
        image_count = 0
        for message in messages:
            content = message.get("content")
            if isinstance(content, str):
                text_chars += len(content)
            elif isinstance(content, list):
                for part in content:
                    if not isinstance(part, dict):
                        continue
                    text = part.get("text")
                    if isinstance(text, str):
                        text_chars += len(text)
                    part_type = part.get("type")
                    if isinstance(part_type, str) and part_type in {"image_url", "input_image"}:
                        image_count += 1
        return cls(
            timing=ContentTiming(started_at),
            request_fields={
                "streaming": streaming,
                "responseModel": response_model,
                "requestedModel": profile.model,
                "reasoningEffort": profile.reasoning_effort.value,
                "maxOutputTokens": profile.max_tokens,
                "inputTextChars": text_chars,
                "messageCount": len(messages),
                "fileAttached": bool(attachments),
                "fileCount": len(attachments),
                "imageCount": image_count,
                "promptCacheLayout": cache_layout,
                "promptCacheRouting": cache_routing,
            },
        )

    def start_attempt(self) -> None:
        self.attempt_started_at = perf_counter()
        self.provider_status_code = None
        self.usage_fields.clear()
        self.provider_model = None
        self.provider_usage_final = False

    def observe_model(self, raw: Any) -> None:
        if isinstance(raw, str) and _MODEL_IDENTIFIER.fullmatch(raw):
            self.provider_model = raw

    def observe_response(self, raw: Any, *, responses_api: bool = False) -> None:
        """Observe a terminal envelope independently of answer/schema validation."""
        if isinstance(raw, dict):
            self.observe_model(raw.get("model"))
            self.observe_usage(raw.get("usage"), responses_api=responses_api, final=True)

    def observe_usage(self, raw: Any, *, responses_api: bool = False, final: bool = False) -> None:
        # Parse each metric independently: optional/bad metadata must not affect output.
        # Streaming values are running snapshots, not increments to add together.
        self.usage_fields.clear()
        self.provider_usage_final = final
        if not isinstance(raw, dict):
            return
        prefix = "input" if responses_api else "prompt"
        output_prefix = "output" if responses_api else "completion"
        input_details = raw.get(f"{prefix}_tokens_details")
        output_details = raw.get(f"{output_prefix}_tokens_details")
        candidates = {
            "inputTokens": raw.get(f"{prefix}_tokens"),
            "outputTokens": raw.get(f"{output_prefix}_tokens"),
            "reasoningTokens": (
                output_details.get("reasoning_tokens") if isinstance(output_details, dict) else None
            ),
            "cachedInputTokens": (
                input_details.get("cached_tokens") if isinstance(input_details, dict) else None
            ),
            "numServerSideToolsUsed": raw.get("num_server_side_tools_used"),
            "costUsdTicks": _cost_usd_ticks(raw),
        }
        self.usage_fields = {
            key: value for key, value in candidates.items() if type(value) is int and value >= 0
        }
        cached = self.usage_fields.get("cachedInputTokens")
        total = self.usage_fields.get("inputTokens")
        if isinstance(cached, int) and isinstance(total, int) and cached > total:
            self.usage_fields.pop("cachedInputTokens")
        details = raw.get("server_side_tool_usage_details")
        if isinstance(details, dict):
            counts = {
                key: value
                for key in _SERVER_SIDE_TOOL_COUNTERS
                if type(value := details.get(key)) is int and value >= 0
            }
            if counts:
                self.usage_fields["serverSideToolUsageDetails"] = counts

    def fields(self) -> dict[str, object]:
        return {
            **self.request_fields,
            **self.timing.fields(),
            **self.usage_fields,
            "providerModel": self.provider_model,
            "providerUsageFinal": self.provider_usage_final if self.usage_fields else None,
            "llmCallId": self.call_id,
            "providerStatusCode": self.provider_status_code,
            "attemptDurationMs": (
                round((perf_counter() - self.attempt_started_at) * 1000, 3)
                if self.attempt_started_at is not None
                else None
            ),
        }


def _observe_content(
    metrics: _CallMetrics, text: str, *, model: str, tool: str, attempt: int
) -> None:
    if metrics.timing.observe(text):
        logger.info(
            "xAI first content available",
            extra={
                **metrics.request_fields,
                **metrics.timing.fields(),
                "llmCallId": metrics.call_id,
                "providerStatusCode": metrics.provider_status_code,
                "providerModel": metrics.provider_model,
                "agent": "Grok",
                "tool": tool,
                "model": model,
                "attempt": attempt,
                "status": "STREAMING",
            },
        )


@asynccontextmanager
async def _shielded_response_stack() -> AsyncIterator[AsyncExitStack]:
    """Close provider responses even when their consuming task is cancelled."""
    stack = AsyncExitStack()
    try:
        yield stack
    finally:
        await shielded_aclose(stack)


def _log_call(
    *,
    metrics: _CallMetrics,
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
            **metrics.fields(),
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
    usage: Any | None = None


class _StreamDelta(_XaiModel):
    content: str | None = None


class _StreamChoice(_XaiModel):
    delta: _StreamDelta


class _StreamChunk(_XaiModel):
    model: str
    choices: list[_StreamChoice] = Field(default_factory=list)
    usage: Any | None = None


class _ResponsesTokenDetails(_XaiModel):
    reasoning_tokens: int | None = Field(default=None, ge=0)


class _ResponsesUsage(_XaiModel):
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    output_tokens_details: _ResponsesTokenDetails | None = None


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
    usage: Any | None = None


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


def _cost_usd_ticks(raw_usage: Any) -> int | None:
    """Keep provider integer ticks exactly; malformed/missing cost is not free."""
    value = raw_usage.get("cost_in_usd_ticks") if isinstance(raw_usage, dict) else None
    return value if type(value) is int and value >= 0 else None


def _attempt_usage(usage: LlmUsage, attempt: int) -> LlmUsage:
    # A transport failure without usage is not proof that xAI did not bill it.
    # Preserve existing token behavior, but never report a partial cost as a total.
    return replace(usage, cost_usd_ticks=None) if attempt > 1 else usage


def _unavailable_usage(model: str | None, *, cost_usd_ticks: int | None = None) -> LlmUsage:
    return LlmUsage(
        model=model,
        input_tokens=None,
        output_tokens=None,
        reasoning_tokens=None,
        cost_usd_ticks=cost_usd_ticks,
    )


def _completion_usage(model: str, raw_usage: Any) -> LlmUsage:
    cost_usd_ticks = _cost_usd_ticks(raw_usage)
    try:
        usage = _CompletionUsage.model_validate(raw_usage)
    except ValidationError:
        return _unavailable_usage(model, cost_usd_ticks=cost_usd_ticks)
    details = usage.completion_tokens_details
    return LlmUsage(
        model=model,
        input_tokens=usage.prompt_tokens,
        output_tokens=usage.completion_tokens,
        reasoning_tokens=details.reasoning_tokens if details is not None else None,
        cost_usd_ticks=cost_usd_ticks,
    )


def _responses_usage(response: _ResponsesResponse) -> LlmUsage:
    if response.status != "completed":
        raise ValueError("Responses output is not complete")
    return _responses_token_usage(response.model, response.usage)


def _responses_token_usage(model: str, raw_usage: Any) -> LlmUsage:
    cost_usd_ticks = _cost_usd_ticks(raw_usage)
    try:
        usage = _ResponsesUsage.model_validate(raw_usage)
    except ValidationError:
        return _unavailable_usage(model, cost_usd_ticks=cost_usd_ticks)
    details = usage.output_tokens_details
    return LlmUsage(
        model=model,
        input_tokens=usage.input_tokens,
        output_tokens=usage.output_tokens,
        reasoning_tokens=details.reasoning_tokens if details is not None else None,
        cost_usd_ticks=cost_usd_ticks,
    )


def _error_response_usage(
    response: httpx.Response, *, attempt: int, responses_api: bool = False
) -> LlmUsage | None:
    """Retain billed metadata even when the provider's body/envelope is invalid."""
    try:
        body = response.json()
    except json.JSONDecodeError, UnicodeDecodeError:
        return None
    if not isinstance(body, dict) or not isinstance(body.get("usage"), dict):
        return None
    model = body.get("model")
    # Do not substitute a requested model name for an unknown actual model.
    if not isinstance(model, str):
        return _attempt_usage(
            _unavailable_usage(None, cost_usd_ticks=_cost_usd_ticks(body["usage"])), attempt
        )
    parse_usage = _responses_token_usage if responses_api else _completion_usage
    return _attempt_usage(parse_usage(model, body["usage"]), attempt)


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
        prompt_cache_layout_enabled: bool = False,
        prompt_cache_routing_enabled: bool = False,
    ) -> None:
        self._client = client
        self._api_key = api_key
        self._cache_layout = prompt_cache_layout_enabled
        self._cache_routing = prompt_cache_routing_enabled

    async def complete_json(
        self,
        *,
        messages: Sequence[LlmMessage],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment] = (),
        prompt_cache: LlmPromptCache | None = None,
    ) -> LlmCompletion[ModelT]:
        if self._cache_layout and prompt_cache is not None:
            messages = stable_prefix_messages(messages, prompt_cache)
        if attachments:
            return await self._complete_json_with_files(
                messages=messages,
                response_model=response_model,
                profile=profile,
                timeout_seconds=timeout_seconds,
                attachments=attachments,
                prompt_cache=prompt_cache,
            )
        started_at = perf_counter()
        metrics = _CallMetrics.start(
            started_at=started_at,
            messages=messages,
            profile=profile,
            attachments=attachments,
            streaming=False,
            response_model=response_model.__name__,
            cache_layout=self._cache_layout and prompt_cache is not None,
            cache_routing=self._cache_routing and prompt_cache is not None,
        )
        if timeout_seconds <= 0:
            _log_call(
                metrics=metrics,
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
            metrics.start_attempt()
            remaining_seconds = deadline - loop.time()
            if remaining_seconds <= 0:
                _log_call(
                    metrics=metrics,
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
                        headers=self._headers(prompt_cache=prompt_cache),
                        json=payload,
                        timeout=self._timeout(remaining_seconds),
                    ) as attempt_response:
                        # Structured output cannot be replayed once HTTP response
                        # headers exist, even if the body later fails while buffering.
                        response_started = True
                        metrics.provider_status_code = attempt_response.status_code
                        await attempt_response.aread()
                        response = attempt_response
            except (httpx.TimeoutException, TimeoutError) as exception:
                _log_call(
                    metrics=metrics,
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
                    metrics=metrics,
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

        try:
            raw_body = response.json()
        except json.JSONDecodeError, UnicodeDecodeError:
            raw_body = None
        metrics.observe_response(raw_body)

        if response.is_error:
            _log_call(
                metrics=metrics,
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
                usage=_error_response_usage(response, attempt=successful_attempt),
            )

        try:
            provider_response = _CompletionResponse.model_validate(response.json())
            content = provider_response.choices[0].message.content
        except (json.JSONDecodeError, ValidationError, IndexError) as exception:
            _log_call(
                metrics=metrics,
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
                usage=_error_response_usage(response, attempt=successful_attempt),
            ) from exception

        usage = _attempt_usage(
            _completion_usage(provider_response.model, provider_response.usage), successful_attempt
        )
        try:
            output = response_model.model_validate_json(content)
        except (json.JSONDecodeError, ValidationError) as exception:
            _log_call(
                metrics=metrics,
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
            metrics=metrics,
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
        prompt_cache: LlmPromptCache | None = None,
    ) -> AsyncIterator[LlmTextStreamItem]:
        if self._cache_layout and prompt_cache is not None:
            messages = stable_prefix_messages(messages, prompt_cache)
        if attachments:
            stream = self._complete_text_stream_with_files(
                messages=messages,
                profile=profile,
                timeout_seconds=timeout_seconds,
                attachments=attachments,
                prompt_cache=prompt_cache,
            )
            async with closing_async_iterator(stream):
                async for item in stream:
                    yield item
            return
        started_at = perf_counter()
        metrics = _CallMetrics.start(
            started_at=started_at,
            messages=messages,
            profile=profile,
            attachments=attachments,
            streaming=True,
            cache_layout=self._cache_layout and prompt_cache is not None,
            cache_routing=self._cache_routing and prompt_cache is not None,
        )
        if timeout_seconds <= 0:
            _log_call(
                metrics=metrics,
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
            metrics.start_attempt()
            remaining_seconds = deadline - loop.time()
            if remaining_seconds <= 0:
                _log_call(
                    metrics=metrics,
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

            provider_usage: LlmUsage | None = None
            provider_model: str | None = None
            completed = False
            response_body_started = False
            response: httpx.Response | None = None
            try:
                async with _shielded_response_stack() as response_stack:
                    async with asyncio.timeout_at(deadline):
                        response = await response_stack.enter_async_context(
                            self._client.stream(
                                "POST",
                                XAI_CHAT_COMPLETIONS_URL,
                                headers=self._headers(
                                    accept_stream=True, prompt_cache=prompt_cache
                                ),
                                json=payload,
                                timeout=self._timeout(remaining_seconds),
                            )
                        )
                    metrics.provider_status_code = response.status_code
                    if response.is_error:
                        _log_call(
                            metrics=metrics,
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
                    response_stack.push_async_callback(shielded_aclose, sse_events)
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
                            metrics.provider_usage_final = True
                            completed = True
                            break
                        try:
                            chunk = _StreamChunk.model_validate_json(data)
                        except (json.JSONDecodeError, ValidationError) as exception:
                            _log_call(
                                metrics=metrics,
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
                        metrics.observe_model(chunk.model)
                        if chunk.usage is not None:
                            metrics.observe_usage(chunk.usage)
                            provider_usage = _completion_usage(chunk.model, chunk.usage)
                        if chunk.choices:
                            text = chunk.choices[0].delta.content
                            if text:
                                _observe_content(
                                    metrics,
                                    text,
                                    model=chunk.model,
                                    tool="chat.completions",
                                    attempt=attempt,
                                )
                                yield LlmTextDelta(text=text)
            except (httpx.TimeoutException, TimeoutError) as exception:
                _log_call(
                    metrics=metrics,
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
                    metrics=metrics,
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
                    metrics=metrics,
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
            if provider_model is None:
                _log_call(
                    metrics=metrics,
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
            _log_call(
                metrics=metrics,
                model=provider_model,
                started_at=started_at,
                status="SUCCESS",
                attempt=attempt,
            )
            yield LlmTextStreamCompleted(
                usage=_attempt_usage(provider_usage or _unavailable_usage(provider_model), attempt)
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
        prompt_cache: LlmPromptCache | None = None,
    ) -> LlmCompletion[ModelT]:
        started_at = perf_counter()
        metrics = _CallMetrics.start(
            started_at=started_at,
            messages=messages,
            profile=profile,
            attachments=attachments,
            streaming=False,
            response_model=response_model.__name__,
            cache_layout=self._cache_layout and prompt_cache is not None,
            cache_routing=self._cache_routing and prompt_cache is not None,
        )
        if timeout_seconds <= 0:
            _log_call(
                metrics=metrics,
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
            prompt_cache=prompt_cache,
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
            metrics.start_attempt()
            remaining_seconds = deadline - loop.time()
            if remaining_seconds <= 0:
                _log_call(
                    metrics=metrics,
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
                        metrics.provider_status_code = attempt_response.status_code
                        await attempt_response.aread()
                        response = attempt_response
            except (httpx.TimeoutException, TimeoutError) as exception:
                _log_call(
                    metrics=metrics,
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
                    metrics=metrics,
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
        try:
            raw_body = response.json()
        except json.JSONDecodeError, UnicodeDecodeError:
            raw_body = None
        metrics.observe_response(raw_body, responses_api=True)

        if response.is_error:
            _log_call(
                metrics=metrics,
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
                usage=_error_response_usage(
                    response, attempt=successful_attempt, responses_api=True
                ),
            )

        try:
            provider_response = _ResponsesResponse.model_validate(response.json())
            content = _responses_output_text(provider_response)
            usage = _attempt_usage(_responses_usage(provider_response), successful_attempt)
        except (json.JSONDecodeError, ValidationError, ValueError) as exception:
            _log_call(
                metrics=metrics,
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
                usage=_error_response_usage(
                    response, attempt=successful_attempt, responses_api=True
                ),
            ) from exception

        try:
            output = response_model.model_validate_json(content)
        except (json.JSONDecodeError, ValidationError) as exception:
            _log_call(
                metrics=metrics,
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
            metrics=metrics,
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
        prompt_cache: LlmPromptCache | None = None,
    ) -> AsyncIterator[LlmTextStreamItem]:
        started_at = perf_counter()
        metrics = _CallMetrics.start(
            started_at=started_at,
            messages=messages,
            profile=profile,
            attachments=attachments,
            streaming=True,
            cache_layout=self._cache_layout and prompt_cache is not None,
            cache_routing=self._cache_routing and prompt_cache is not None,
        )
        if timeout_seconds <= 0:
            _log_call(
                metrics=metrics,
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
            prompt_cache=prompt_cache,
        )
        payload["stream"] = True
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout_seconds

        for attempt in range(1, _MAX_NETWORK_ATTEMPTS + 1):
            metrics.start_attempt()
            remaining_seconds = deadline - loop.time()
            if remaining_seconds <= 0:
                _log_call(
                    metrics=metrics,
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
                async with _shielded_response_stack() as response_stack:
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
                    metrics.provider_status_code = response.status_code
                    if response.is_error:
                        _log_call(
                            metrics=metrics,
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
                    response_stack.push_async_callback(shielded_aclose, sse_events)
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
                            if event_type in {"response.created", "response.in_progress"}:
                                envelope = event.get("response")
                                if isinstance(envelope, dict):
                                    metrics.observe_model(envelope.get("model"))
                            elif event_type in {
                                "response.completed",
                                "response.failed",
                                "response.incomplete",
                            }:
                                metrics.observe_response(event.get("response"), responses_api=True)
                            if event_type == "response.output_text.delta":
                                delta = event.get("delta")
                                if not isinstance(delta, str):
                                    raise ValueError("Responses text delta is invalid")
                                if delta:
                                    text_parts.append(delta)
                                    _observe_content(
                                        metrics,
                                        delta,
                                        model=profile.model,
                                        tool="responses",
                                        attempt=attempt,
                                    )
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
                                    metrics=metrics,
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
                                metrics=metrics,
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
                    metrics=metrics,
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
                    metrics=metrics,
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
                    metrics=metrics,
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
                terminal_text = _responses_output_text(provider_response)
                usage = _attempt_usage(_responses_usage(provider_response), attempt)
            except ValueError as exception:
                _log_call(
                    metrics=metrics,
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

            delivered_text = "".join(text_parts)
            if delivered_text != terminal_text:
                if not terminal_text.startswith(delivered_text):
                    _log_call(
                        metrics=metrics,
                        model=provider_response.model,
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
                    )
                missing_suffix = terminal_text[len(delivered_text) :]
                logger.warning(
                    "xAI Responses stream omitted a terminal text suffix",
                    extra={
                        "model": provider_response.model,
                        "deltaChars": len(delivered_text),
                        "terminalChars": len(terminal_text),
                        "recoveredChars": len(missing_suffix),
                    },
                )
                text_parts.append(missing_suffix)
                _observe_content(
                    metrics,
                    missing_suffix,
                    model=provider_response.model,
                    tool="responses",
                    attempt=attempt,
                )
                yield LlmTextDelta(text=missing_suffix)

            self._warn_model_mismatch(expected=profile.model, actual=provider_response.model)
            _log_call(
                metrics=metrics,
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
        files_first: bool = False,
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
        if files_first:
            first_user_index = next(
                index for index, item in enumerate(inputs) if item.get("role") == "user"
            )
            inputs.insert(first_user_index, {"role": "user", "content": file_parts})
            return inputs
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
        prompt_cache: LlmPromptCache | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": profile.model,
            "input": self._responses_input(
                messages=messages,
                attachments=attachments,
                files_first=self._cache_layout and prompt_cache is not None,
            ),
            "reasoning": {"effort": profile.reasoning_effort.value},
            "max_output_tokens": profile.max_tokens,
            "store": False,
        }
        if profile.temperature is not None:
            payload["temperature"] = profile.temperature
        if self._cache_routing and prompt_cache is not None:
            payload["prompt_cache_key"] = prompt_cache.key
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

    def _headers(
        self, *, accept_stream: bool = False, prompt_cache: LlmPromptCache | None = None
    ) -> dict[str, str]:
        headers = {
            "Authorization": f"Bearer {self._api_key.get_secret_value()}",
            "Content-Type": "application/json",
        }
        if accept_stream:
            headers["Accept"] = "text/event-stream"
        if self._cache_routing and prompt_cache is not None:
            headers["x-grok-conv-id"] = prompt_cache.key
        return headers

    @staticmethod
    def _timeout(timeout_seconds: float) -> httpx.Timeout:
        if timeout_seconds <= 0:
            raise LlmBridgeError(
                category=ErrorCategory.TIMEOUT,
                retryable=True,
            )
        return httpx.Timeout(timeout_seconds)
