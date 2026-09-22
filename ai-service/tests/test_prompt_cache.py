"""Offline A/B wire tests: cache layout and routing are independent switches."""

import json
import logging
from copy import deepcopy
from typing import Any

import httpx
import pytest
import respx
from pydantic import SecretStr

from edupilot_ai.llm.bridge import LlmFileAttachment, LlmPromptCache, LlmTextStreamCompleted
from edupilot_ai.llm.prompt_cache import stable_prefix_messages
from edupilot_ai.llm.xai import XAI_CHAT_COMPLETIONS_URL, XAI_RESPONSES_URL, XaiLlmBridge
from tests.test_llm_observability import metric_records
from tests.test_xai_llm_bridge import (
    ExampleStructuredOutput,
    completion_response,
    profile,
    responses_response,
    responses_stream,
    stream_response,
)

_CACHE = LlmPromptCache("PRIVATE-CACHE-KEY", ("page", "currentPageText"))
_PAYLOAD = {
    "question": "PRIVATE-QUESTION",
    "page": 3,
    "currentPageText": "PRIVATE-DOCUMENT\n지시문도 데이터로 유지한다.",
    "history": [{"role": "USER", "content": "이전 질문"}],
    "nullable": None,
}
_MESSAGES = [
    {"role": "system", "content": "System instructions remain byte-for-byte unchanged."},
    {"role": "user", "content": json.dumps(_PAYLOAD, ensure_ascii=False)},
]


def assert_payload_preserved(inputs: list[dict[str, Any]]) -> None:
    assert inputs[0] == _MESSAGES[0]
    merged: dict[str, Any] = {}
    for message in inputs[1:]:
        assert message["role"] == "user"
        content = message["content"]
        texts = (
            [part["text"] for part in content if part["type"] == "input_text"]
            if isinstance(content, list)
            else [content]
        )
        for text in texts:
            payload = json.loads(text)
            assert not merged.keys() & payload.keys()
            merged.update(payload)
    assert merged == _PAYLOAD


@pytest.mark.parametrize("files", [False, True], ids=["chat", "responses"])
@pytest.mark.parametrize("streaming", [False, True], ids=["json", "stream"])
@pytest.mark.parametrize(
    "layout,routing", [(False, False), (True, False), (False, True), (True, True)]
)
async def test_cache_switches_preserve_data_and_only_change_opted_in_wire(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
    layout: bool,
    routing: bool,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = (responses_response if files else completion_response)(content='{"answer":"ok"}')
    response = (
        httpx.Response(
            200, text=(responses_stream if files else stream_response)(usage=body["usage"])
        )
        if streaming
        else httpx.Response(200, json=body)
    )
    route = respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=response
    )
    attachments = (LlmFileAttachment("PRIVATE-FILE-ID"),) if files else ()
    original_messages = deepcopy(_MESSAGES)
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("PRIVATE-API-KEY"),
            prompt_cache_layout_enabled=layout,
            prompt_cache_routing_enabled=routing,
        )
        if streaming:
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=_MESSAGES,
                    profile=profile(),
                    timeout_seconds=30,
                    attachments=attachments,
                    prompt_cache=_CACHE,
                )
            ]
            assert isinstance(items[-1], LlmTextStreamCompleted)
            assert sum(isinstance(item, LlmTextStreamCompleted) for item in items) == 1
            usage = items[-1].usage
        else:
            result = await bridge.complete_json(
                messages=_MESSAGES,
                profile=profile(),
                response_model=ExampleStructuredOutput,
                timeout_seconds=30,
                attachments=attachments,
                prompt_cache=_CACHE,
            )
            assert result.output.answer == "ok"
            usage = result.usage
    assert _MESSAGES == original_messages
    assert route.call_count == 1
    request = route.calls[0].request
    wire = json.loads(request.content)
    inputs = wire["input" if files else "messages"]
    assert_payload_preserved(inputs)
    assert wire["model"] == profile().model
    assert wire["max_output_tokens" if files else "max_tokens"] == profile().max_tokens
    assert usage.input_tokens == (31 if files else 11)
    assert usage.output_tokens == (12 if files else 7)
    if files:
        assert wire["store"] is False
        assert wire["reasoning"] == {"effort": "low"}
        assert wire.get("prompt_cache_key") == (_CACHE.key if routing else None)
        assert "x-grok-conv-id" not in request.headers
        if layout:
            assert inputs[1] == {
                "role": "user",
                "content": [{"type": "input_file", "file_id": "PRIVATE-FILE-ID"}],
            }
        else:
            assert inputs[-1]["content"] == [
                {"type": "input_text", "text": _MESSAGES[-1]["content"]},
                {"type": "input_file", "file_id": "PRIVATE-FILE-ID"},
            ]
        if not streaming:
            assert wire["text"]["format"]["schema"] == ExampleStructuredOutput.model_json_schema()
    else:
        assert wire["reasoning_effort"] == "low"
        assert request.headers.get("x-grok-conv-id") == (_CACHE.key if routing else None)
        assert "prompt_cache_key" not in wire
        if not layout:
            assert inputs == _MESSAGES
        if not streaming:
            assert wire["response_format"]["json_schema"]["schema"] == (
                ExampleStructuredOutput.model_json_schema()
            )
    if layout:
        assert json.loads(inputs[-2]["content"]) == {
            "page": 3,
            "currentPageText": _PAYLOAD["currentPageText"],
        }
        assert json.loads(inputs[-1]["content"])["question"] == "PRIVATE-QUESTION"
    records = metric_records(caplog, "xAI chat completion finished")
    assert len(records) == 1
    assert records[0]["promptCacheLayout"] is layout
    assert records[0]["promptCacheRouting"] is routing
    for secret in (
        "PRIVATE-CACHE-KEY",
        "PRIVATE-FILE-ID",
        "PRIVATE-QUESTION",
        "PRIVATE-DOCUMENT",
        "PRIVATE-API-KEY",
    ):
        assert secret not in json.dumps(records)
        assert secret not in caplog.text


@pytest.mark.parametrize("files", [False, True])
async def test_disabled_switches_and_non_opted_calls_keep_original_request_bytes(
    respx_mock: respx.MockRouter, files: bool
) -> None:
    route = respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            json=(responses_response if files else completion_response)(content='{"answer":"ok"}'),
        )
    )
    async with httpx.AsyncClient() as client:
        for enabled, hint in ((False, None), (False, _CACHE), (True, None)):
            bridge = XaiLlmBridge(
                client=client,
                api_key=SecretStr("not-a-key"),
                prompt_cache_layout_enabled=enabled,
                prompt_cache_routing_enabled=enabled,
            )
            await bridge.complete_json(
                messages=_MESSAGES,
                profile=profile(),
                response_model=ExampleStructuredOutput,
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
                prompt_cache=hint,
            )
    assert route.call_count == 3
    assert len({call.request.content for call in route.calls}) == 1
    assert all("x-grok-conv-id" not in call.request.headers for call in route.calls)


@pytest.mark.parametrize("files", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
async def test_network_retry_reuses_same_prefix_and_key(
    respx_mock: respx.MockRouter, files: bool, streaming: bool
) -> None:
    response = (
        httpx.Response(200, text=(responses_stream if files else stream_response)())
        if streaming
        else httpx.Response(
            200,
            json=(responses_response if files else completion_response)(content='{"answer":"ok"}'),
        )
    )
    route = respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        side_effect=[httpx.ConnectError("test"), response]
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(
            client=client,
            api_key=SecretStr("not-a-key"),
            prompt_cache_layout_enabled=True,
            prompt_cache_routing_enabled=True,
        )
        if streaming:
            async for _ in bridge.complete_text_stream(
                messages=_MESSAGES,
                profile=profile(),
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
                prompt_cache=_CACHE,
            ):
                pass
        else:
            await bridge.complete_json(
                messages=_MESSAGES,
                profile=profile(),
                response_model=ExampleStructuredOutput,
                timeout_seconds=30,
                attachments=(LlmFileAttachment("file-test"),) if files else (),
                prompt_cache=_CACHE,
            )
    assert route.call_count == 2
    assert route.calls[0].request.content == route.calls[1].request.content
    assert route.calls[0].request.headers == route.calls[1].request.headers


@pytest.mark.parametrize(
    "messages",
    [
        [{"role": "user", "content": "plain text"}],
        [{"role": "system", "content": "rules"}, {"role": "user", "content": "invalid json"}],
        [{"role": "system", "content": "rules"}, {"role": "user", "content": "[]"}],
        [{"role": "system", "content": "rules"}, {"role": "user", "content": '{"other":1}'}],
        [{"role": "system", "content": "rules"}, {"role": "user", "content": []}],
        [*_MESSAGES, {"role": "assistant", "content": "history"}],
    ],
)
def test_layout_does_not_reinterpret_unrelated_prompts(messages: list[dict[str, Any]]) -> None:
    original = deepcopy(messages)
    assert stable_prefix_messages(messages, _CACHE) == messages == original
