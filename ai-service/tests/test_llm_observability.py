"""Log-only latency/cache measurements with mocked provider I/O."""

import json
import logging
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

import httpx
import pytest
import respx
from pydantic import SecretStr

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.core.logging import JsonLogFormatter
from edupilot_ai.llm.bridge import LlmBridgeError, LlmFileAttachment, LlmTextDelta
from edupilot_ai.llm.xai import XAI_CHAT_COMPLETIONS_URL, XAI_RESPONSES_URL, XaiLlmBridge
from edupilot_ai.settings import RuntimeEnvironment
from tests.test_xai_llm_bridge import (
    ExampleStructuredOutput,
    completion_response,
    profile,
    responses_response,
    responses_stream,
    stream_response,
)

MISSING = object()


def metric_records(caplog: pytest.LogCaptureFixture, message: str) -> list[dict[str, Any]]:
    formatter = JsonLogFormatter(environment=RuntimeEnvironment.PROD)
    return [
        json.loads(formatter.format(record))
        for record in caplog.records
        if record.getMessage() == message
    ]


@dataclass
class TestClock:
    __test__ = False
    now: float = 1000.0

    def __call__(self) -> float:
        return self.now


class TimedProviderStream(httpx.AsyncByteStream):
    def __init__(self, clock: TestClock, frames: list[tuple[float, object]]) -> None:
        self.clock = clock
        self.frames = frames

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for elapsed, event in self.frames:
            self.clock.now = 1000.0 + elapsed
            data = event if event == "[DONE]" else json.dumps(event)
            yield f"data: {data}\n\n".encode()


@pytest.mark.parametrize("files", [False, True], ids=["chat", "responses"])
@pytest.mark.parametrize("streaming", [False, True], ids=["json", "stream"])
@pytest.mark.parametrize("cached", [6, 0, MISSING, None, -1, True, 1.5, "6", 1000])
async def test_cache_metrics_are_optional_and_do_not_change_output(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
    cached: object,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = (responses_response if files else completion_response)(content='{"answer":"safe"}')
    usage = body["usage"]
    assert isinstance(usage, dict)
    if cached is not MISSING:
        usage["input_tokens_details" if files else "prompt_tokens_details"] = {
            "cached_tokens": cached
        }
    wire = (
        httpx.Response(200, text=(responses_stream if files else stream_response)(usage=usage))
        if streaming
        else httpx.Response(200, json=body)
    )
    route = respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=wire
    )
    attachments = (LlmFileAttachment("PRIVATE-FILE-ID"),) if files else ()
    messages = [{"role": "user", "content": "PRIVATE-QUESTION"}]
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("PRIVATE-API-KEY"))
        if streaming:
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=messages,
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=attachments,
                )
            ]
            assert any(isinstance(item, LlmTextDelta) for item in items)
        else:
            result = await bridge.complete_json(
                messages=messages,
                profile=profile(),
                response_model=ExampleStructuredOutput,
                timeout_seconds=30,
                attachments=attachments,
            )
            assert result.output.answer == "safe"

    assert route.call_count == 1
    records = metric_records(caplog, "xAI chat completion finished")
    assert len(records) == 1
    record = records[0]
    assert record["inputTokens"] == (31 if files else 11)
    assert record["outputTokens"] == (12 if files else 7)
    assert record["reasoningTokens"] == (4 if files else 3)
    if type(cached) is int and 0 <= cached <= record["inputTokens"]:
        assert record["cachedInputTokens"] == cached
    else:
        assert "cachedInputTokens" not in record
    assert record["streaming"] is streaming
    assert record["fileAttached"] is files
    assert record["fileCount"] == int(files)
    assert record["inputTextChars"] == len("PRIVATE-QUESTION")
    assert record["providerStatusCode"] == 200
    assert record["requestedModel"] == record["model"] == "grok-4.5"
    assert record["reasoningEffort"] == "low"
    assert record["maxOutputTokens"] == 4096
    assert record["attemptDurationMs"] <= record["durationMs"]
    assert ("firstContentMs" in record) is streaming
    assert bool(record["llmCallId"])
    for secret in ("PRIVATE-FILE-ID", "PRIVATE-QUESTION", "PRIVATE-API-KEY"):
        assert secret not in json.dumps(records)
        assert secret not in caplog.text


@pytest.mark.parametrize("files", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("raw_usage", [None, "malformed", {"prompt_tokens": "bad"}])
async def test_missing_usage_is_not_logged_as_zero(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
    raw_usage: object,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = (responses_response if files else completion_response)(content='{"answer":"ok"}')
    body["usage"] = raw_usage
    respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=(
            httpx.Response(
                200, text=(responses_stream if files else stream_response)(usage=raw_usage)
            )
            if streaming
            else httpx.Response(200, json=body)
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        if streaming:
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "question"}],
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=(LlmFileAttachment("file-test"),) if files else (),
                )
            ]
            assert items
        else:
            result = await bridge.complete_json(
                messages=[{"role": "user", "content": "question"}],
                profile=profile(),
                response_model=ExampleStructuredOutput,
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
            )
            assert result.output.answer == "ok"
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert all(
        key not in record
        for key in ("inputTokens", "outputTokens", "reasoningTokens", "cachedInputTokens")
    )


@pytest.mark.parametrize("files", [False, True])
async def test_first_last_content_and_completion_have_distinct_times(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    monkeypatch: pytest.MonkeyPatch,
    files: bool,
) -> None:
    clock = TestClock()
    monkeypatch.setattr("edupilot_ai.llm.xai.perf_counter", clock)
    monkeypatch.setattr("edupilot_ai.core.observability.perf_counter", clock)
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    frames: list[tuple[float, object]]
    if files:
        frames = [
            (1, {"type": "response.created"}),
            (2, {"type": "response.reasoning_text.delta", "delta": "PRIVATE-REASONING"}),
            (3, {"type": "response.output_text.delta", "delta": " "}),
            (5, {"type": "response.output_text.delta", "delta": "첫 "}),
            (8, {"type": "response.output_text.delta", "delta": "본문"}),
            (
                10,
                {"type": "response.completed", "response": responses_response(content=" 첫 본문")},
            ),
            (12, "[DONE]"),
        ]
    else:
        frames = [
            (at, {"model": "grok-4.5", "choices": [{"delta": {"content": text}}]})
            for at, text in [(1, ""), (3, " "), (5, "첫 "), (8, "본문")]
        ]
        frames.extend([(10, {"model": "grok-4.5", "choices": []}), (12, "[DONE]")])
    respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, stream=TimedProviderStream(clock, frames))
    )
    async with httpx.AsyncClient() as client:
        items = [
            item
            async for item in XaiLlmBridge(
                client=client, api_key=SecretStr("test-only")
            ).complete_text_stream(
                messages=[{"role": "user", "content": "question"}],
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
            )
        ]
    assert "".join(item.text for item in items if isinstance(item, LlmTextDelta)) == " 첫 본문"
    first = metric_records(caplog, "xAI first content available")
    assert len(first) == 1
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["llmCallId"] == first[0]["llmCallId"]
    assert first[0]["firstContentMs"] == record["firstContentMs"] == 5000
    assert record["lastContentMs"] == 8000
    assert record["contentSpanMs"] == 3000
    assert record["durationMs"] == 12000
    assert "PRIVATE-REASONING" not in caplog.text


async def test_transport_attempts_share_call_id_but_not_http_status_or_usage(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        side_effect=[
            httpx.RemoteProtocolError("PRIVATE-TRANSPORT-DETAIL"),
            httpx.Response(200, json=completion_response(content='{"answer":"ok"}')),
            httpx.Response(429),
        ]
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        await bridge.complete_json(
            messages=[],
            response_model=ExampleStructuredOutput,
            profile=profile(),
            timeout_seconds=30,
        )
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=30,
            )
    assert caught.value.category is ErrorCategory.INTERNAL
    assert route.call_count == 3  # HTTP 429 still is not automatically retried here.
    first, second, other = metric_records(caplog, "xAI chat completion finished")
    assert first["llmCallId"] == second["llmCallId"] != other["llmCallId"]
    assert [first["attempt"], second["attempt"], other["attempt"]] == [1, 2, 1]
    assert first["status"] == "RETRYING"
    assert "providerStatusCode" not in first
    assert "inputTokens" not in first
    assert second["providerStatusCode"] == 200
    assert other["providerStatusCode"] == 429
    assert other["failureKind"] == "rate_limit"
    assert "PRIVATE-TRANSPORT-DETAIL" not in caplog.text


async def test_image_payload_counts_text_only_and_never_logs_base64(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, json=completion_response(content='{"answer":"ok"}'))
    )
    async with httpx.AsyncClient() as client:
        await XaiLlmBridge(client=client, api_key=SecretStr("test-only")).complete_json(
            messages=[
                {"role": "system", "content": "PRIVATE-SYSTEM"},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "PRIVATE-TEXT"},
                        {
                            "type": "image_url",
                            "image_url": {"url": "data:image/png;base64,PRIVATE-BASE64"},
                        },
                    ],
                },
            ],
            response_model=ExampleStructuredOutput,
            profile=profile(),
            timeout_seconds=30,
        )
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["inputTextChars"] == len("PRIVATE-SYSTEMPRIVATE-TEXT")
    assert record["imageCount"] == 1
    assert record["messageCount"] == 2
    assert record["responseModel"] == "ExampleStructuredOutput"
    assert "PRIVATE" not in json.dumps(record)
    assert "PRIVATE" not in caplog.text


@pytest.mark.parametrize("files", [False, True])
async def test_rejected_schema_still_logs_reported_usage(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture, files: bool
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = (responses_response if files else completion_response)(content='{"wrong":"PRIVATE"}')
    respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, json=body)
    )
    async with httpx.AsyncClient() as client:
        with pytest.raises(LlmBridgeError):
            await XaiLlmBridge(client=client, api_key=SecretStr("test-only")).complete_json(
                messages=[{"role": "user", "content": "question"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
            )
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["status"] == "FAILED"
    assert record["errorCode"] == "SCHEMA"
    assert record["inputTokens"] == (31 if files else 11)
    assert "PRIVATE" not in caplog.text


@pytest.mark.parametrize("files", [False, True])
async def test_usage_is_not_duplicated_on_first_content_log(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture, files: bool
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = (responses_response if files else completion_response)(content="first")
    # Responses may recover all text from the terminal response; Chat may deliver
    # usage alongside its first text. Both have usage available at first content.
    event = (
        {"type": "response.completed", "response": body}
        if files
        else {
            "model": "grok-4.5",
            "choices": [{"delta": {"content": "first"}}],
            "usage": body["usage"],
        }
    )
    respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, text=f"data: {json.dumps(event)}\n\ndata: [DONE]\n\n")
    )
    async with httpx.AsyncClient() as client:
        items = [
            item
            async for item in XaiLlmBridge(
                client=client, api_key=SecretStr("test-only")
            ).complete_text_stream(
                messages=[{"role": "user", "content": "question"}],
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
            )
        ]
    assert "".join(item.text for item in items if isinstance(item, LlmTextDelta)) == "first"
    first = metric_records(caplog, "xAI first content available")
    assert len(first) == 1
    assert (
        not {"inputTokens", "outputTokens", "reasoningTokens", "cachedInputTokens"}
        & first[0].keys()
    )
    terminal = metric_records(caplog, "xAI chat completion finished")
    assert len(terminal) == 1
    assert terminal[0]["inputTokens"] == (31 if files else 11)
