"""Exact provider cost propagation without real xAI calls."""

import json
from pathlib import Path

import httpx
import pytest
import respx
from pydantic import SecretStr, ValidationError

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmFileAttachment,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmUsage,
)
from edupilot_ai.llm.xai import XAI_CHAT_COMPLETIONS_URL, XAI_RESPONSES_URL, XaiLlmBridge
from edupilot_ai.models.base import Usage
from edupilot_ai.models.doc_chat import DocChatCompletion
from edupilot_ai.models.plan import AgentOutput, ToolName
from edupilot_ai.usage import combine_llm_usages, response_usage, unknown_llm_usage
from tests.fakes import FakeLlm
from tests.planner_fixtures import planner_output
from tests.test_captions import caption_output, captions_payload
from tests.test_doc_chat import doc_chat_payload
from tests.test_turn_contract import make_plan
from tests.test_xai_llm_bridge import (
    ExampleStructuredOutput,
    completion_response,
    profile,
    responses_response,
    responses_stream,
    stream_response,
)

EXACT_TICKS = 9_007_199_254_740_993  # Larger than float's exact-integer range.
MISSING = object()


@pytest.mark.parametrize("file_api", [False, True], ids=["chat", "responses"])
@pytest.mark.parametrize("streaming", [False, True], ids=["json", "stream"])
@pytest.mark.parametrize("cost", [EXACT_TICKS, 0, MISSING, None, -1, True, 1.25, "123"])
async def test_provider_cost_is_exact_or_unknown_without_breaking_output(
    respx_mock: respx.MockRouter, file_api: bool, streaming: bool, cost: object
) -> None:
    body = (
        responses_response(content='{"answer":"자료 근거"}')
        if file_api
        else completion_response(content='{"answer":"자료 근거"}')
    )
    raw_usage = body["usage"]
    assert isinstance(raw_usage, dict)
    if cost is not MISSING:
        raw_usage["cost_in_usd_ticks"] = cost
    wire_response = (
        httpx.Response(
            200,
            text=(
                responses_stream(usage=raw_usage) if file_api else stream_response(usage=raw_usage)
            ),
        )
        if streaming
        else httpx.Response(200, json=body)
    )
    route = respx_mock.post(XAI_RESPONSES_URL if file_api else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=wire_response
    )
    attachments = (LlmFileAttachment("file-test"),) if file_api else ()
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        if streaming:
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "자료 질문"}],
                    profile=profile(),
                    timeout_seconds=10,
                    attachments=attachments,
                )
            ]
            assert all(isinstance(item, LlmTextDelta) for item in items[:-1])
            terminal = items[-1]
            assert isinstance(terminal, LlmTextStreamCompleted)
            usage = terminal.usage
        else:
            result = await bridge.complete_json(
                messages=[{"role": "user", "content": "자료 질문"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=10,
                attachments=attachments,
            )
            assert result.output.answer == "자료 근거"
            usage = result.usage
    expected = cost if type(cost) is int and cost >= 0 else None
    assert usage.cost_usd_ticks == expected
    assert usage.input_tokens == (31 if file_api else 11)
    assert route.call_count == 1


@pytest.mark.parametrize("file_api", [False, True], ids=["chat", "responses"])
async def test_schema_rejected_generation_preserves_billed_usage(
    respx_mock: respx.MockRouter, file_api: bool
) -> None:
    body = (
        responses_response(content='{"wrong":"shape"}')
        if file_api
        else completion_response(content='{"wrong":"shape"}')
    )
    raw_usage = body["usage"]
    assert isinstance(raw_usage, dict)
    raw_usage["cost_in_usd_ticks"] = EXACT_TICKS
    respx_mock.post(XAI_RESPONSES_URL if file_api else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, json=body)
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "자료 질문"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=10,
                attachments=(LlmFileAttachment("file-test"),) if file_api else (),
            )
    assert caught.value.category is ErrorCategory.SCHEMA
    assert caught.value.usage is not None
    assert caught.value.usage.cost_usd_ticks == EXACT_TICKS


@pytest.mark.parametrize("file_api", [False, True], ids=["chat", "responses"])
@pytest.mark.parametrize("status_code", [200, 500])
async def test_invalid_provider_envelope_still_preserves_billed_cost(
    respx_mock: respx.MockRouter, file_api: bool, status_code: int
) -> None:
    body = (
        responses_response(content="invalid envelope")
        if file_api
        else completion_response(content="invalid envelope")
    )
    body["output" if file_api else "choices"] = []
    raw_usage = body["usage"]
    assert isinstance(raw_usage, dict)
    raw_usage["cost_in_usd_ticks"] = EXACT_TICKS
    respx_mock.post(XAI_RESPONSES_URL if file_api else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(status_code, json=body)
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        with pytest.raises(LlmBridgeError) as caught:
            await bridge.complete_json(
                messages=[{"role": "user", "content": "자료 질문"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=10,
                attachments=(LlmFileAttachment("file-test"),) if file_api else (),
            )
    assert caught.value.category is (
        ErrorCategory.SCHEMA if status_code == 200 else ErrorCategory.INTERNAL
    )
    assert caught.value.usage is not None
    assert caught.value.usage.cost_usd_ticks == EXACT_TICKS


@pytest.mark.parametrize("file_api", [False, True], ids=["chat", "responses"])
async def test_valid_cost_survives_missing_or_malformed_token_metadata(
    respx_mock: respx.MockRouter, file_api: bool
) -> None:
    body = (
        responses_response(content='{"answer":"자료 근거"}')
        if file_api
        else completion_response(content='{"answer":"자료 근거"}')
    )
    body["usage"] = {"cost_in_usd_ticks": EXACT_TICKS, "output_tokens": "invalid"}
    respx_mock.post(XAI_RESPONSES_URL if file_api else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, json=body)
    )
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        result = await bridge.complete_json(
            messages=[{"role": "user", "content": "자료 질문"}],
            response_model=ExampleStructuredOutput,
            profile=profile(),
            timeout_seconds=10,
            attachments=(LlmFileAttachment("file-test"),) if file_api else (),
        )
    usage = response_usage([result.usage])
    assert result.output.answer == "자료 근거"
    assert usage is not None
    assert usage.cost_usd_ticks == EXACT_TICKS
    assert usage.input_tokens is None
    assert usage.output_tokens is None


@pytest.mark.parametrize("file_api", [False, True], ids=["chat", "responses"])
@pytest.mark.parametrize("streaming", [False, True], ids=["json", "stream"])
async def test_network_retry_does_not_report_only_final_attempt_cost(
    respx_mock: respx.MockRouter, file_api: bool, streaming: bool
) -> None:
    body = (
        responses_response(content='{"answer":"recovered"}')
        if file_api
        else completion_response(content='{"answer":"recovered"}')
    )
    raw_usage = body["usage"]
    assert isinstance(raw_usage, dict)
    raw_usage["cost_in_usd_ticks"] = 100
    final_response = (
        httpx.Response(
            200,
            text=responses_stream(usage=raw_usage)
            if file_api
            else stream_response(usage=raw_usage),
        )
        if streaming
        else httpx.Response(200, json=body)
    )
    route = respx_mock.post(XAI_RESPONSES_URL if file_api else XAI_CHAT_COMPLETIONS_URL).mock(
        side_effect=[httpx.RemoteProtocolError("no response"), final_response]
    )
    attachments = (LlmFileAttachment("file-test"),) if file_api else ()
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("test-only"))
        if streaming:
            items = [
                item
                async for item in bridge.complete_text_stream(
                    messages=[{"role": "user", "content": "자료 질문"}],
                    profile=profile(),
                    timeout_seconds=10,
                    attachments=attachments,
                )
            ]
            terminal = items[-1]
            assert isinstance(terminal, LlmTextStreamCompleted)
            usage = terminal.usage
        else:
            result = await bridge.complete_json(
                messages=[{"role": "user", "content": "자료 질문"}],
                response_model=ExampleStructuredOutput,
                profile=profile(),
                timeout_seconds=10,
                attachments=attachments,
            )
            usage = result.usage
    assert route.call_count == 2
    assert usage.cost_usd_ticks is None
    assert usage.input_tokens is not None  # Existing token behavior is unchanged.


@pytest.mark.parametrize("cost", [EXACT_TICKS, 0, None])
async def test_doc_chat_cost_reaches_wire_without_renaming_existing_fields(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    cost: int | None,
) -> None:
    fake_llm.queue_completion(
        DocChatCompletion(answer="자료에 근거한 답변입니다."),
        LlmUsage("grok-4.5", 123, 45, 6, cost_usd_ticks=cost),
    )
    response = await client.post(
        "/internal/ai/doc-chat", headers=auth_headers, json=doc_chat_payload()
    )
    assert response.status_code == 200
    usage = response.json()["usage"]
    assert usage.get("cost_usd_ticks") == cost
    assert "costUsdTicks" not in usage
    assert usage["inputTokens"] == 123
    assert usage["outputTokens"] == 45
    assert usage["reasoningTokens"] == 6
    assert len(fake_llm.calls) == 1


@pytest.mark.parametrize("streaming", [False, True], ids=["json", "ndjson"])
@pytest.mark.parametrize("retry", [False, True], ids=["two-calls", "schema-retry"])
async def test_turn_sums_all_costs_once_in_final_usage(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    streaming: bool,
    retry: bool,
) -> None:
    if retry:
        fake_llm.queue(
            LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
                usage=LlmUsage("grok-4.5", 10, 2, 1, cost_usd_ticks=11),
            )
        )
    fake_llm.queue_completion(
        planner_output(
            make_plan(
                ToolName.ANSWER_QUESTION,
                {"qaThreadMode": "START_NEW", "threadRef": None},
                "사용자 질문 답변",
            )
        ),
        LlmUsage("grok-4.5", 20, 3, 2, cost_usd_ticks=EXACT_TICKS),
    )
    answer = "편차는 평균과 관측값의 차이입니다."
    answer_usage = LlmUsage("grok-4.5", 30, 4, 3, cost_usd_ticks=7)
    if streaming:
        fake_llm.queue_text_stream(answer, usage=answer_usage)
    else:
        fake_llm.queue_completion(AgentOutput(markdown=answer), answer_usage)
    response = await client.post(
        "/internal/ai/turn",
        headers={
            **auth_headers,
            "Accept": "application/x-ndjson" if streaming else "application/json",
        },
        json=turn_payload,
    )
    assert response.status_code == 200
    if streaming:
        events = [json.loads(line) for line in response.text.splitlines()]
        assert sum(event["type"] == "completed" for event in events) == 1
        assert events[-1]["type"] == "completed"
        assert all(
            "usage" not in event and "cost_usd_ticks" not in json.dumps(event)
            for event in events[:-1]
        )
        body = events[-1]["result"]
        assert (
            "".join(event["text"] for event in events if event["type"] == "content_delta") == answer
        )
        assert response.text.count('"cost_usd_ticks"') == 1
    else:
        body = response.json()
    assert body["usage"]["cost_usd_ticks"] == EXACT_TICKS + 7 + (11 if retry else 0)
    assert body["usage"]["inputTokens"] == 50 + (10 if retry else 0)
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == 2 + int(retry)


@pytest.mark.parametrize("failed_cost", [20, None])
async def test_captions_include_failed_page_cost_or_leave_total_unknown(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    failed_cost: int | None,
) -> None:
    fake_llm.queue_completion(caption_output("그래프"), LlmUsage("vision", 1, 1, 0, 10))
    fake_llm.queue(
        LlmBridgeError(
            category=ErrorCategory.SCHEMA,
            retryable=False,
            usage=LlmUsage("vision", 2, 2, 0, failed_cost),
        )
    )
    fake_llm.queue_completion(caption_output(None), LlmUsage("vision", 3, 3, 0, 30))
    response = await client.post(
        "/internal/ai/captions", headers=auth_headers, json=captions_payload()
    )
    assert response.status_code == 200
    body = response.json()
    assert len(body["warnings"]) == 1
    assert body["usage"].get("cost_usd_ticks") == (60 if failed_cost is not None else None)
    assert body["usage"]["inputTokens"] == 6


def test_cost_aggregation_keeps_unknown_distinct_from_zero_and_handles_mixed_models() -> None:
    known = LlmUsage("a", 1, 2, 3, EXACT_TICKS)
    mixed = combine_llm_usages([known, LlmUsage("b", 2, 3, 4, 1)])
    assert mixed.model is None
    assert mixed.cost_usd_ticks == EXACT_TICKS + 1
    incomplete = response_usage([known, LlmUsage("a", 2, 3, 4)])
    assert incomplete is not None
    assert incomplete.input_tokens == 3
    assert incomplete.cost_usd_ticks is None
    assert unknown_llm_usage("a").cost_usd_ticks is None
    assert combine_llm_usages([]).cost_usd_ticks is None
    assert response_usage([]) is None
    deterministic = response_usage([], include_zero_when_empty=True)
    assert deterministic is not None
    assert deterministic.cost_usd_ticks is None


def test_cost_is_preserved_when_only_token_counts_are_missing() -> None:
    result = response_usage([LlmUsage("a", None, None, None, EXACT_TICKS)])
    assert result is not None
    assert result.input_tokens is None
    assert result.cost_usd_ticks == EXACT_TICKS


@pytest.mark.parametrize("cost", [-1, True, 1.5, "123"])
def test_wire_cost_requires_exact_nonnegative_integer(cost: object) -> None:
    with pytest.raises(ValidationError):
        Usage.model_validate({"cost_usd_ticks": cost})


@pytest.mark.parametrize(
    ("filename", "model_name"),
    [
        ("exam-draft.schema.json", "ExamDraftResponse"),
        ("report-agent.schema.json", "ReportGenerateResponse"),
        ("report-agent.schema.json", "ReportQueryResponse"),
    ],
)
def test_published_contract_usage_matches_shared_model(filename: str, model_name: str) -> None:
    contracts = Path(__file__).resolve().parents[2] / "docs" / "contracts"
    document = json.loads((contracts / filename).read_text())
    assert document["schemas"][model_name]["$defs"]["Usage"] == Usage.model_json_schema(
        by_alias=True
    )
