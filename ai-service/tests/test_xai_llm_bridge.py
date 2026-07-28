"""Mocked wire-contract tests for the xAI LLM adapter."""

import json
import logging

import httpx
import pytest
import respx
from pydantic import BaseModel, SecretStr

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmUsage,
)
from edupilot_ai.llm.xai import XAI_CHAT_COMPLETIONS_URL, XaiLlmBridge
from edupilot_ai.settings import AgentLlmProfile, ReasoningEffort


class ExampleStructuredOutput(BaseModel):
    answer: str


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


async def test_xai_bridge_sends_strict_structured_output_wire_format(
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
        result = await bridge.complete_json(
            messages=[
                {"role": "system", "content": "Return structured output."},
                {"role": "user", "content": "test"},
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
    respx_mock.post(XAI_CHAT_COMPLETIONS_URL).mock(
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
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "test"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=180,
            )

    assert caught.value.category is ErrorCategory.INTERNAL
    assert caught.value.retryable is True


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
        items = [
            item
            async for item in bridge.complete_text_stream(
                messages=[{"role": "user", "content": "편차를 설명해 줘"}],
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
    assert [
        item.text for item in items if isinstance(item, LlmTextDelta)
    ] == ["편차는 ", "평균과의 차이입니다."]
    terminal = items[-1]
    assert isinstance(terminal, LlmTextStreamCompleted)
    assert terminal.usage == LlmUsage(
        model="grok-4.5",
        input_tokens=13,
        output_tokens=9,
        reasoning_tokens=2,
    )


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
