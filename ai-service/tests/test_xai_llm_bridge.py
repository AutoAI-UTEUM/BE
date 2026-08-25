"""Mocked wire-contract tests for the xAI LLM adapter."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator

import httpx
import pytest
import respx
from pydantic import BaseModel, SecretStr

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.factory import _xai_http_limits
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmFileAttachment,
    LlmMessage,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmUsage,
)
from edupilot_ai.llm.xai import XAI_CHAT_COMPLETIONS_URL, XAI_RESPONSES_URL, XaiLlmBridge
from edupilot_ai.settings import AgentLlmProfile, ReasoningEffort


class ExampleStructuredOutput(BaseModel):
    answer: str


class FailingAsyncByteStream(httpx.AsyncByteStream):
    def __init__(self, first_chunk: bytes, exception: httpx.RequestError) -> None:
        self._first_chunk = first_chunk
        self._exception = exception

    async def __aiter__(self) -> AsyncIterator[bytes]:
        yield self._first_chunk
        raise self._exception


class DelayedAsyncByteStream(httpx.AsyncByteStream):
    def __init__(self, chunks: list[bytes], delay_seconds: float) -> None:
        self._chunks = chunks
        self._delay_seconds = delay_seconds

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for chunk in self._chunks:
            await asyncio.sleep(self._delay_seconds)
            yield chunk


def profile() -> AgentLlmProfile:
    return AgentLlmProfile(
        model="grok-4.5",
        reasoning_effort=ReasoningEffort.LOW,
        max_tokens=4096,
        temperature=None,
    )


def completion_response(
    *,
    content: str,
    model: str = "grok-4.5",
) -> dict[str, object]:
    return {
        "id": "completion-test",
        "object": "chat.completion",
        "model": model,
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": 11,
            "completion_tokens": 7,
            "total_tokens": 21,
            "completion_tokens_details": {"reasoning_tokens": 3},
        },
    }


def responses_response(
    *,
    content: str,
    model: str = "grok-4.5",
) -> dict[str, object]:
    return {
        "id": "response-test",
        "object": "response",
        "model": model,
        "status": "completed",
        "output": [
            {"type": "reasoning", "summary": []},
            {"type": "file_search_call", "status": "completed"},
            {
                "type": "message",
                "role": "assistant",
                "status": "completed",
                "content": [{"type": "output_text", "text": content}],
            },
        ],
        "usage": {
            "input_tokens": 31,
            "output_tokens": 12,
            "output_tokens_details": {"reasoning_tokens": 4},
        },
    }


def responses_stream(*, include_done: bool = False) -> str:
    events = [
        {"type": "response.created", "response": {"status": "in_progress"}},
        {"type": "response.file_search_call.in_progress", "item_id": "search-1"},
        {"type": "response.output_text.delta", "delta": "첨부 문서의 "},
        {"type": "response.reasoning_text.delta", "delta": "PRIVATE-REASONING"},
        {"type": "response.output_text.delta", "delta": "근거입니다."},
        {
            "type": "response.completed",
            "response": responses_response(content="첨부 문서의 근거입니다."),
        },
    ]
    frames = [f"data: {json.dumps(event, ensure_ascii=False)}" for event in events]
    if include_done:
        frames.append("data: [DONE]")
    return "\n\n".join(frames) + "\n\n"


async def test_xai_bridge_uses_responses_wire_for_structured_file_attachment(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            json=responses_response(content='{"answer":"첨부 근거"}'),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.xai"):
            result = await bridge.complete_json(
                messages=[
                    {"role": "system", "content": "PRIVATE-SYSTEM"},
                    {"role": "user", "content": "PRIVATE-ANCHOR"},
                ],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment(file_id="file-private-123"),),
            )

    payload = json.loads(route.calls[0].request.content)
    assert payload == {
        "model": "grok-4.5",
        "input": [
            {"role": "system", "content": "PRIVATE-SYSTEM"},
            {
                "role": "user",
                "content": [
                    {"type": "input_text", "text": "PRIVATE-ANCHOR"},
                    {"type": "input_file", "file_id": "file-private-123"},
                ],
            },
        ],
        "reasoning_effort": "low",
        "max_output_tokens": 4096,
        "store": False,
        "text": {
            "format": {
                "type": "json_schema",
                "name": "ExampleStructuredOutput",
                "schema": ExampleStructuredOutput.model_json_schema(),
                "strict": True,
            }
        },
    }
    assert result.output.answer == "첨부 근거"
    assert result.usage == LlmUsage(
        model="grok-4.5",
        input_tokens=31,
        output_tokens=12,
        reasoning_tokens=4,
    )
    assert "file-private-123" not in caplog.text
    assert "PRIVATE-SYSTEM" not in caplog.text
    assert "PRIVATE-ANCHOR" not in caplog.text
    call_log = next(
        record for record in caplog.records if record.message == "xAI chat completion finished"
    )
    assert call_log.__dict__["tool"] == "responses"


async def test_xai_responses_structured_retries_before_headers(
    respx_mock: respx.MockRouter,
) -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.RemoteProtocolError("stale response", request=request)
        return httpx.Response(
            200,
            json=responses_response(content='{"answer":"recovered"}'),
        )

    route = respx_mock.post(XAI_RESPONSES_URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        result = await bridge.complete_json(
            messages=[{"role": "user", "content": "anchor"}],
            response_model=ExampleStructuredOutput,
            profile=profile(),
            timeout_seconds=30,
            attachments=(LlmFileAttachment(file_id="file-test"),),
        )

    assert result.output.answer == "recovered"
    assert len(route.calls) == 2


async def test_xai_responses_structured_does_not_retry_after_headers(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            stream=FailingAsyncByteStream(
                b'{"id":"partial",',
                httpx.ReadError("response interrupted"),
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "anchor"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment(file_id="file-test"),),
            )

    assert caught.value.category is ErrorCategory.INTERNAL
    assert len(route.calls) == 1


async def test_xai_responses_structured_enforces_total_deadline(
    respx_mock: respx.MockRouter,
) -> None:
    response_body = json.dumps(responses_response(content='{"answer":"too slow"}')).encode()
    route = respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "application/json"},
            stream=DelayedAsyncByteStream(
                [response_body[index : index + 1] for index in range(10)],
                delay_seconds=0.01,
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "anchor"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=0.025,
                attachments=(LlmFileAttachment(file_id="file-test"),),
            )

    assert caught.value.category is ErrorCategory.TIMEOUT
    assert len(route.calls) == 1


async def test_xai_responses_classifies_invalid_structured_output(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            json=responses_response(content='{"unexpected":"field"}'),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "anchor"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment(file_id="file-test"),),
            )

    assert caught.value.category is ErrorCategory.SCHEMA
    assert caught.value.retryable is False
    assert caught.value.usage is not None


@pytest.mark.parametrize(
    ("status_code", "retryable"),
    [(400, False), (429, True), (503, True)],
)
async def test_xai_responses_classifies_provider_failures(
    respx_mock: respx.MockRouter,
    status_code: int,
    retryable: bool,
) -> None:
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(status_code, json={"error": {"message": "private"}})
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "anchor"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment(file_id="file-test"),),
            )

    assert caught.value.category is ErrorCategory.INTERNAL
    assert caught.value.retryable is retryable


@pytest.mark.parametrize("include_done", [False, True])
async def test_xai_bridge_streams_only_responses_output_text_with_file(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
    include_done: bool,
) -> None:
    route = respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            text=responses_stream(include_done=include_done),
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.xai"):
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "PRIVATE-ANCHOR"}],
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=(LlmFileAttachment(file_id="file-private-stream"),),
                )
            ]

    payload = json.loads(route.calls[0].request.content)
    assert payload["stream"] is True
    assert payload["store"] is False
    assert "text" not in payload
    assert "messages" not in payload
    assert [item.text for item in items if isinstance(item, LlmTextDelta)] == [
        "첨부 문서의 ",
        "근거입니다.",
    ]
    terminal = items[-1]
    assert isinstance(terminal, LlmTextStreamCompleted)
    assert terminal.usage.input_tokens == 31
    assert terminal.usage.output_tokens == 12
    assert terminal.usage.reasoning_tokens == 4
    assert "PRIVATE-REASONING" not in caplog.text
    assert "file-private-stream" not in caplog.text


async def test_xai_responses_stream_rejects_non_object_event_as_schema(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            text="data: []\n\n",
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "anchor"}],
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=(LlmFileAttachment(file_id="file-test"),),
                )
            ]

    assert caught.value.category is ErrorCategory.SCHEMA
    assert caught.value.retryable is False


@pytest.mark.parametrize(
    "stream_body",
    [
        'data: {"type":"response.failed","response":{"status":"failed"}}\n\n',
        'data: {"type":"response.created","response":{"status":"in_progress"}}\n\n',
    ],
)
async def test_xai_responses_stream_requires_successful_terminal_event(
    respx_mock: respx.MockRouter,
    stream_body: str,
) -> None:
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            text=stream_body,
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "anchor"}],
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=(LlmFileAttachment(file_id="file-test"),),
                )
            ]

    assert caught.value.category is ErrorCategory.INTERNAL
    assert caught.value.retryable is True


async def test_xai_responses_stream_rejects_delta_terminal_mismatch(
    respx_mock: respx.MockRouter,
) -> None:
    mismatched_stream = responses_stream().replace(
        '"text": "첨부 문서의 근거입니다."',
        '"text": "종단 응답이 서로 다릅니다."',
    )
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            text=mismatched_stream,
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "anchor"}],
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=(LlmFileAttachment(file_id="file-test"),),
                )
            ]

    assert caught.value.category is ErrorCategory.SCHEMA
    assert caught.value.retryable is False


async def test_xai_responses_stream_retries_before_first_body_byte(
    respx_mock: respx.MockRouter,
) -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.RemoteProtocolError("stale response", request=request)
        return httpx.Response(
            200,
            text=responses_stream(),
            headers={"Content-Type": "text/event-stream"},
        )

    route = respx_mock.post(XAI_RESPONSES_URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        items = [
            item
            async for item in bridge.complete_text_stream(
                messages=[{"role": "user", "content": "anchor"}],
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment(file_id="file-test"),),
            )
        ]

    assert len(route.calls) == 2
    assert isinstance(items[-1], LlmTextStreamCompleted)


async def test_xai_responses_stream_does_not_retry_after_provider_event_byte(
    respx_mock: respx.MockRouter,
) -> None:
    first_event = json.dumps({"type": "response.created"}).encode()
    route = respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            stream=FailingAsyncByteStream(
                b"data: " + first_event + b"\n\n",
                httpx.ReadError("stream interrupted"),
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("xai-test-not-real"))
        with pytest.raises(LlmBridgeError):
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "anchor"}],
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=(LlmFileAttachment(file_id="file-test"),),
                )
            ]

    assert len(route.calls) == 1


async def test_xai_bridge_sends_strict_structured_output_wire_format(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            json=completion_response(content='{"answer":"grounded"}'),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.xai"):
            result = await bridge.complete_json(
                messages=[
                    {"role": "system", "content": "PRIVATE-PDF-TEXT"},
                    {"role": "user", "content": "PRIVATE-STUDENT-ANSWER"},
                ],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=180,
            )

    request = route.calls[0].request
    payload = json.loads(request.content)
    assert request.headers["Authorization"] == "Bearer xai-test-not-real"
    assert payload["model"] == "grok-4.5"
    assert payload["reasoning_effort"] == "low"
    assert payload["max_tokens"] == 4096
    assert payload["messages"] == [
        {"role": "system", "content": "PRIVATE-PDF-TEXT"},
        {"role": "user", "content": "PRIVATE-STUDENT-ANSWER"},
    ]
    assert "temperature" not in payload
    assert payload["response_format"]["type"] == "json_schema"
    assert payload["response_format"]["json_schema"]["strict"] is True
    assert (
        payload["response_format"]["json_schema"]["schema"]["properties"]["answer"]["type"]
        == "string"
    )
    assert result.output.answer == "grounded"
    assert result.usage.model == "grok-4.5"
    assert result.usage.input_tokens == 11
    assert result.usage.output_tokens == 7
    assert result.usage.reasoning_tokens == 3
    assert "PRIVATE-PDF-TEXT" not in caplog.text
    assert "PRIVATE-STUDENT-ANSWER" not in caplog.text
    assert "grounded" not in caplog.text
    call_log = next(
        record for record in caplog.records if record.message == "xAI chat completion finished"
    )
    assert call_log.__dict__["model"] == "grok-4.5"
    assert call_log.__dict__["status"] == "SUCCESS"
    assert call_log.__dict__["attempt"] == 1


async def test_xai_bridge_sends_multimodal_image_content_parts(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            json=completion_response(content='{"answer":"시각 요소 설명"}'),
        )
    )
    messages: list[LlmMessage] = [
        {"role": "system", "content": "시각 요소만 설명하라."},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": "추출 텍스트"},
                {
                    "type": "image_url",
                    "image_url": {
                        "url": "data:image/png;base64,aW1hZ2U=",
                        "detail": "high",
                    },
                },
            ],
        },
    ]

    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        result = await bridge.complete_json(
            messages=messages,
            response_model=ExampleStructuredOutput,
            profile=profile(),
            timeout_seconds=60,
        )

    payload = json.loads(route.calls[0].request.content)
    assert payload["messages"] == messages
    assert payload["response_format"]["type"] == "json_schema"
    assert result.output.answer == "시각 요소 설명"


async def test_xai_bridge_warns_when_provider_model_differs(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            json=completion_response(
                content='{"answer":"redirected"}',
                model="grok-redirected",
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.WARNING):
            result = await bridge.complete_json(
                messages=[{"role": "user", "content": "test"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=180,
            )

    assert result.usage.model == "grok-redirected"
    assert "expected=grok-4.5 actual=grok-redirected" in caplog.text


async def test_xai_bridge_classifies_timeout(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        side_effect=httpx.ReadTimeout("test timeout")
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "test"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=1,
            )

    assert caught.value.category is ErrorCategory.TIMEOUT
    assert caught.value.retryable is True
    assert len(route.calls) == 1


async def test_xai_bridge_enforces_total_structured_deadline_across_chunks(
    respx_mock: respx.MockRouter,
) -> None:
    response_body = json.dumps(completion_response(content='{"answer":"too slow"}')).encode()
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "application/json"},
            stream=DelayedAsyncByteStream(
                [response_body[index : index + 1] for index in range(10)],
                delay_seconds=0.01,
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "test"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=0.025,
            )

    assert caught.value.category is ErrorCategory.TIMEOUT
    assert len(route.calls) == 1


async def test_xai_bridge_retries_network_errors_twice_with_remaining_budget(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    timeouts: list[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        timeout = request.extensions["timeout"]
        assert isinstance(timeout, dict)
        read_timeout = timeout["read"]
        assert isinstance(read_timeout, float)
        timeouts.append(read_timeout)
        if len(timeouts) == 1:
            raise httpx.ConnectError("PRIVATE-CONNECTION-DETAIL", request=request)
        if len(timeouts) == 2:
            raise httpx.RemoteProtocolError(
                "PRIVATE-PROTOCOL-DETAIL",
                request=request,
            )
        return httpx.Response(
            200,
            json=completion_response(content='{"answer":"recovered"}'),
        )

    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.xai"):
            result = await bridge.complete_json(
                messages=[{"role": "user", "content": "PRIVATE-PROMPT"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=10,
            )

    assert result.output.answer == "recovered"
    assert len(route.calls) == 3
    assert timeouts[0] > timeouts[1] > timeouts[2] > 0
    call_logs = [
        record for record in caplog.records if record.message == "xAI chat completion finished"
    ]
    assert [record.__dict__["attempt"] for record in call_logs] == [1, 2, 3]
    assert [record.__dict__["status"] for record in call_logs] == [
        "RETRYING",
        "RETRYING",
        "SUCCESS",
    ]
    assert [record.__dict__["exceptionType"] for record in call_logs[:2]] == [
        "ConnectError",
        "RemoteProtocolError",
    ]
    assert "PRIVATE-CONNECTION-DETAIL" not in caplog.text
    assert "PRIVATE-PROTOCOL-DETAIL" not in caplog.text
    assert "PRIVATE-PROMPT" not in caplog.text


async def test_xai_bridge_stops_after_three_network_attempts(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    def fail(request: httpx.Request) -> httpx.Response:
        raise httpx.RemoteProtocolError("provider disconnected", request=request)

    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(side_effect=fail)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.WARNING, logger="edupilot_ai.llm.xai"):
            with pytest.raises(LlmBridgeError) as caught:
                await bridge.complete_json(
                    messages=[{"role": "user", "content": "test"}],
                    response_model=ExampleStructuredOutput,
                    profile=profile(),
                    timeout_seconds=10,
                )

    assert caught.value.category is ErrorCategory.INTERNAL
    assert caught.value.retryable is True
    assert len(route.calls) == 3
    call_logs = [
        record for record in caplog.records if record.message == "xAI chat completion finished"
    ]
    assert [record.__dict__["status"] for record in call_logs] == [
        "RETRYING",
        "RETRYING",
        "FAILED",
    ]


async def test_xai_bridge_does_not_retry_after_structured_response_starts(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            stream=FailingAsyncByteStream(
                b'{"id":"partial",',
                httpx.ReadError("response interrupted"),
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "test"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=10,
            )

    assert caught.value.category is ErrorCategory.INTERNAL
    assert len(route.calls) == 1


async def test_xai_bridge_classifies_invalid_structured_output(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            json=completion_response(content='{"unexpected":"field"}'),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "test"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=180,
            )

    assert caught.value.category is ErrorCategory.SCHEMA
    assert caught.value.retryable is False
    assert caught.value.usage == LlmUsage(
        model="grok-4.5",
        input_tokens=11,
        output_tokens=7,
        reasoning_tokens=3,
    )


async def test_xai_bridge_classifies_retryable_provider_failure(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(503, json={"error": {"message": "not exposed"}})
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.WARNING, logger="edupilot_ai.llm.xai"):
            with pytest.raises(LlmBridgeError) as caught:
                await bridge.complete_json(
                    messages=[{"role": "user", "content": "test"}],
                    response_model=ExampleStructuredOutput,
                    profile=profile(),
                    timeout_seconds=180,
                )

    assert caught.value.category is ErrorCategory.INTERNAL
    assert caught.value.retryable is True
    call_log = next(
        record for record in caplog.records if record.message == "xAI chat completion finished"
    )
    assert call_log.__dict__["failureKind"] == "provider"
    assert call_log.__dict__["errorCode"] == "INTERNAL"
    assert len(respx_mock.calls) == 1


async def test_xai_bridge_logs_rate_limit_without_provider_body(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    private_provider_message = "PRIVATE-PROVIDER-ERROR"
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            429,
            json={"error": {"message": private_provider_message}},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.WARNING, logger="edupilot_ai.llm.xai"):
            with pytest.raises(LlmBridgeError):
                await bridge.complete_json(
                    messages=[{"role": "user", "content": "PRIVATE-PROMPT"}],
                    response_model=ExampleStructuredOutput,
                    profile=profile(),
                    timeout_seconds=180,
                )

    call_log = next(
        record for record in caplog.records if record.message == "xAI chat completion finished"
    )
    assert call_log.__dict__["failureKind"] == "rate_limit"
    assert call_log.__dict__["attempt"] == 1
    assert private_provider_message not in caplog.text
    assert "PRIVATE-PROMPT" not in caplog.text


def stream_response() -> str:
    chunks = [
        {
            "id": "stream-test",
            "object": "chat.completion.chunk",
            "model": "grok-4.5",
            "choices": [
                {
                    "index": 0,
                    "delta": {"role": "assistant", "content": "편차는 "},
                }
            ],
        },
        {
            "id": "stream-test",
            "object": "chat.completion.chunk",
            "model": "grok-4.5",
            "choices": [
                {
                    "index": 0,
                    "delta": {"content": "평균과의 차이입니다."},
                }
            ],
        },
        {
            "id": "stream-test",
            "object": "chat.completion.chunk",
            "model": "grok-4.5",
            "choices": [],
            "usage": {
                "prompt_tokens": 13,
                "completion_tokens": 9,
                "completion_tokens_details": {"reasoning_tokens": 2},
            },
        },
    ]
    frames = [f"data: {json.dumps(chunk, ensure_ascii=False)}" for chunk in chunks]
    frames.append("data: [DONE]")
    return "\n\n".join(frames) + "\n\n"


async def test_xai_bridge_streams_markdown_without_response_format(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            text=stream_response(),
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.xai"):
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "PRIVATE-STREAM-PROMPT"}],
                    profile=profile(),
                    timeout_seconds=42.5,
                )
            ]

    request = route.calls[0].request
    payload = json.loads(request.content)
    assert request.headers["Accept"] == "text/event-stream"
    assert payload["stream"] is True
    assert payload["stream_options"] == {"include_usage": True}
    assert "response_format" not in payload
    assert [item.text for item in items if isinstance(item, LlmTextDelta)] == [
        "편차는 ",
        "평균과의 차이입니다.",
    ]
    terminal = items[-1]
    assert isinstance(terminal, LlmTextStreamCompleted)
    assert terminal.usage == LlmUsage(
        model="grok-4.5",
        input_tokens=13,
        output_tokens=9,
        reasoning_tokens=2,
    )
    assert "PRIVATE-STREAM-PROMPT" not in caplog.text
    assert "평균과의 차이입니다." not in caplog.text
    call_log = next(
        record for record in caplog.records if record.message == "xAI chat completion finished"
    )
    assert call_log.__dict__["status"] == "SUCCESS"


async def test_xai_bridge_retries_stream_before_response_starts(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.RemoteProtocolError("stale connection", request=request)
        return httpx.Response(
            200,
            text=stream_response(),
            headers={"Content-Type": "text/event-stream"},
        )

    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.xai"):
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "test"}],
                    profile=profile(),
                    timeout_seconds=30,
                )
            ]

    assert len(route.calls) == 2
    assert [item.text for item in items if isinstance(item, LlmTextDelta)] == [
        "편차는 ",
        "평균과의 차이입니다.",
    ]
    call_logs = [
        record for record in caplog.records if record.message == "xAI chat completion finished"
    ]
    assert [record.__dict__["attempt"] for record in call_logs] == [1, 2]
    assert call_logs[0].__dict__["exceptionType"] == "RemoteProtocolError"


async def test_xai_bridge_retries_stream_after_headers_before_body(
    respx_mock: respx.MockRouter,
) -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            return httpx.Response(
                200,
                headers={"Content-Type": "text/event-stream"},
                stream=FailingAsyncByteStream(
                    b"",
                    httpx.ReadError("closed before first body byte", request=request),
                ),
            )
        return httpx.Response(
            200,
            text=stream_response(),
            headers={"Content-Type": "text/event-stream"},
        )

    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        items = [
            item
            async for item in bridge.complete_text_stream(
                messages=[{"role": "user", "content": "test"}],
                profile=profile(),
                timeout_seconds=30,
            )
        ]

    assert len(route.calls) == 2
    assert [item.text for item in items if isinstance(item, LlmTextDelta)] == [
        "편차는 ",
        "평균과의 차이입니다.",
    ]


async def test_xai_bridge_does_not_retry_stream_after_compressed_raw_body_starts(
    respx_mock: respx.MockRouter,
) -> None:
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(
            200,
            headers={
                "Content-Type": "text/event-stream",
                "Content-Encoding": "gzip",
            },
            stream=FailingAsyncByteStream(
                b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x02\xff",
                httpx.ReadError("closed after raw gzip header", request=request),
            ),
        )

    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "test"}],
                    profile=profile(),
                    timeout_seconds=30,
                )
            ]

    assert caught.value.category is ErrorCategory.INTERNAL
    assert len(route.calls) == 1
    assert attempts == 1


async def test_xai_bridge_does_not_retry_stream_after_first_delta(
    respx_mock: respx.MockRouter,
) -> None:
    first_chunk = {
        "model": "grok-4.5",
        "choices": [{"delta": {"content": "첫 청크"}}],
    }
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            stream=FailingAsyncByteStream(
                f"data: {json.dumps(first_chunk, ensure_ascii=False)}\n\n".encode(),
                httpx.ReadError("stream interrupted"),
            ),
        )
    )
    items: list[LlmTextDelta | LlmTextStreamCompleted] = []
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            async for item in bridge.complete_text_stream(
                messages=[{"role": "user", "content": "test"}],
                profile=profile(),
                timeout_seconds=30,
            ):
                items.append(item)

    assert caught.value.category is ErrorCategory.INTERNAL
    assert [item.text for item in items if isinstance(item, LlmTextDelta)] == ["첫 청크"]
    assert len(route.calls) == 1


async def test_xai_bridge_enforces_total_stream_deadline_across_chunks(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            headers={"Content-Type": "text/event-stream"},
            stream=DelayedAsyncByteStream(
                [b": provider-heartbeat\n\n"] * 10,
                delay_seconds=0.01,
            ),
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "test"}],
                    profile=profile(),
                    timeout_seconds=0.025,
                )
            ]

    assert caught.value.category is ErrorCategory.TIMEOUT
    assert len(route.calls) == 1


async def test_xai_bridge_checks_deadline_after_slow_stream_consumer(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            text=stream_response(),
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        stream = bridge.complete_text_stream(
            messages=[{"role": "user", "content": "test"}],
            profile=profile(),
            timeout_seconds=0.025,
        ).__aiter__()
        first_item = await anext(stream)
        await asyncio.sleep(0.03)
        with pytest.raises(LlmBridgeError) as caught:
            await anext(stream)

    assert isinstance(first_item, LlmTextDelta)
    assert caught.value.category is ErrorCategory.TIMEOUT
    assert len(route.calls) == 1


def test_xai_http_pool_shortens_expiry_without_removing_default_caps() -> None:
    limits = _xai_http_limits()

    assert limits.max_connections == 100
    assert limits.max_keepalive_connections == 20
    assert limits.keepalive_expiry == 3.0


async def test_xai_bridge_classifies_malformed_stream_chunk(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            text="data: not-json\n\ndata: [DONE]\n\n",
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "test"}],
                    profile=profile(),
                    timeout_seconds=30,
                )
            ]

    assert caught.value.category is ErrorCategory.SCHEMA
    assert caught.value.retryable is False


async def test_xai_bridge_rejects_stream_without_done(
    respx_mock: respx.MockRouter,
) -> None:
    response_without_done = stream_response().replace("data: [DONE]\n\n", "")
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            text=response_without_done,
            headers={"Content-Type": "text/event-stream"},
        )
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("xai-test-not-real"),
        )
        with pytest.raises(LlmBridgeError) as caught:
            _ = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "test"}],
                    profile=profile(),
                    timeout_seconds=30,
                )
            ]

    assert caught.value.category is ErrorCategory.INTERNAL
    assert caught.value.retryable is True
