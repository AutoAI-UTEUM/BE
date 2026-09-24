"""Provider-reported model/tool/cost telemetry without real network calls."""

import json
import logging
from typing import Any

import httpx
import pytest
import respx
from pydantic import SecretStr

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmFileAttachment,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmUsage,
)
from edupilot_ai.llm.xai import (
    XAI_CHAT_COMPLETIONS_URL,
    XAI_RESPONSES_URL,
    XaiLlmBridge,
    _CallMetrics,
)
from tests.test_llm_observability import metric_records
from tests.test_xai_llm_bridge import (
    ExampleStructuredOutput,
    FailingAsyncByteStream,
    completion_response,
    profile,
    responses_response,
)

PROVIDER_MODEL = "grok-4.5-202609"
CONTENT = '{"answer":"PRIVATE-ANSWER"}'
EXACT_TICKS = 9_007_199_254_740_993
WORK_FIELDS = {"numServerSideToolsUsed", "serverSideToolUsageDetails", "costUsdTicks"}


def provider_body(files: bool) -> dict[str, Any]:
    body = (responses_response if files else completion_response)(
        content=CONTENT, model=PROVIDER_MODEL
    )
    return dict(body)


def add_work_usage(body: dict[str, Any]) -> None:
    body["usage"].update(
        {
            "num_server_side_tools_used": 3,
            "cost_in_usd_ticks": EXACT_TICKS,
            "server_side_tool_usage_details": {
                "document_search_calls": 2,
                "file_search_calls": 1,
                "web_search_calls": 0,
                "mcp_calls": "PRIVATE-ARGUMENTS",
                "PRIVATE-TOOL-NAME": 7,
                "queries": ["PRIVATE-SEARCH-QUERY"],
            },
        }
    )


def frames(files: bool, body: dict[str, Any]) -> list[dict[str, Any]]:
    if files:
        return [
            {"type": "response.created", "response": {"model": body["model"]}},
            {"type": "response.output_text.delta", "delta": CONTENT},
            {"type": "response.completed", "response": body},
        ]
    return [
        {"model": body["model"], "choices": [{"delta": {"content": CONTENT}}]},
        {"model": body["model"], "choices": [], "usage": body["usage"]},
    ]


def sse(events: list[dict[str, Any]], *, done: bool = True) -> str:
    return "".join(f"data: {json.dumps(event)}\n\n" for event in events) + (
        "data: [DONE]\n\n" if done else ""
    )


async def invoke(files: bool, streaming: bool) -> LlmUsage:
    async with httpx.AsyncClient() as client:
        bridge = XaiLlmBridge(client=client, api_key=SecretStr("PRIVATE-API-KEY"))
        messages = [{"role": "user", "content": "PRIVATE-QUESTION"}]
        attachments = (LlmFileAttachment("PRIVATE-FILE-ID"),) if files else ()
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
            assert "".join(item.text for item in items if isinstance(item, LlmTextDelta)) == CONTENT
            assert sum(isinstance(item, LlmTextStreamCompleted) for item in items) == 1
            terminal = items[-1]
            assert isinstance(terminal, LlmTextStreamCompleted)
            return terminal.usage
        result = await bridge.complete_json(
            messages=messages,
            response_model=ExampleStructuredOutput,
            profile=profile(),
            timeout_seconds=30,
            attachments=attachments,
        )
        assert result.output.answer == "PRIVATE-ANSWER"
        return result.usage


def mock_response(
    respx_mock: respx.MockRouter, files: bool, streaming: bool, body: dict[str, Any]
) -> respx.Route:
    return respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=(
            httpx.Response(200, text=sse(frames(files, body)))
            if streaming
            else httpx.Response(200, json=body)
        )
    )


@pytest.mark.parametrize("files", [False, True], ids=["chat", "responses"])
@pytest.mark.parametrize("streaming", [False, True], ids=["json", "stream"])
async def test_reported_work_is_safe_exact_and_only_logged_once(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)
    add_work_usage(body)
    route = mock_response(respx_mock, files, streaming, body)
    usage = await invoke(files, streaming)
    assert route.call_count == 1
    assert usage.model == PROVIDER_MODEL
    assert usage.cost_usd_ticks == EXACT_TICKS
    assert usage.input_tokens == (31 if files else 11)
    terminal = metric_records(caplog, "xAI chat completion finished")
    assert len(terminal) == 1
    record = terminal[0]
    assert record["requestedModel"] == "grok-4.5"
    assert record["providerModel"] == record["model"] == PROVIDER_MODEL
    if not streaming:
        assert record["responseModel"] == "ExampleStructuredOutput"
    assert record["numServerSideToolsUsed"] == 3
    assert record["serverSideToolUsageDetails"] == {
        "document_search_calls": 2,
        "file_search_calls": 1,
        "web_search_calls": 0,
    }
    assert record["costUsdTicks"] == EXACT_TICKS
    assert record["providerUsageFinal"] is True
    assert record["status"] == "SUCCESS"
    for first in metric_records(caplog, "xAI first content available"):
        assert not WORK_FIELDS & first.keys()
        assert "providerUsageFinal" not in first
        assert first["providerModel"] == PROVIDER_MODEL
    assert "PRIVATE" not in json.dumps(terminal)
    assert "PRIVATE" not in caplog.text


@pytest.mark.parametrize("files", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("invalid", [None, -1, True, 1.5, "3", {}, []])
async def test_bad_work_metadata_never_breaks_the_answer(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
    invalid: object,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)
    body["usage"].update(
        {
            "num_server_side_tools_used": invalid,
            "cost_in_usd_ticks": invalid,
            "server_side_tool_usage_details": {
                "document_search_calls": invalid,
                "file_search_calls": 0,
            },
        }
    )
    mock_response(respx_mock, files, streaming, body)
    usage = await invoke(files, streaming)
    assert usage.cost_usd_ticks is None
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert "costUsdTicks" not in record
    assert "numServerSideToolsUsed" not in record
    assert record["serverSideToolUsageDetails"] == {"file_search_calls": 0}


@pytest.mark.parametrize("files", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("zero", [False, True])
async def test_missing_is_not_free_and_tools_are_not_inferred_from_output(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
    zero: bool,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)  # Responses fixture includes a file_search_call output item.
    if zero:
        body["usage"].update({"num_server_side_tools_used": 0, "cost_in_usd_ticks": 0})
    mock_response(respx_mock, files, streaming, body)
    await invoke(files, streaming)
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert "serverSideToolUsageDetails" not in record
    for key in ("numServerSideToolsUsed", "costUsdTicks"):
        if zero:
            assert record[key] == 0
        else:
            assert key not in record


@pytest.mark.parametrize("files", [False, True])
async def test_stream_snapshots_are_replaced_not_summed(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture, files: bool
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)
    add_work_usage(body)
    events = frames(files, body)
    running = {
        "cost_in_usd_ticks": 123,
        "num_server_side_tools_used": 1,
        "server_side_tool_usage_details": {"document_search_calls": 1},
    }
    if files:
        events[0]["response"]["usage"] = running  # Not a terminal usage report.
    else:
        events[0]["usage"] = running
    events.append(events[-1])  # Repeated cumulative usage must not double-count.
    respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(200, text=sse(events))
    )
    await invoke(files, True)
    records = metric_records(caplog, "xAI chat completion finished")
    assert len(records) == 1
    assert records[0]["costUsdTicks"] == EXACT_TICKS
    assert records[0]["numServerSideToolsUsed"] == 3
    assert records[0]["serverSideToolUsageDetails"]["document_search_calls"] == 2
    assert records[0]["providerUsageFinal"] is True


@pytest.mark.parametrize("files", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
async def test_retry_logs_attempt_cost_without_claiming_complete_request_cost(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    streaming: bool,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)
    add_work_usage(body)
    route = respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        side_effect=[
            httpx.ConnectError("PRIVATE-TRANSPORT"),
            (
                httpx.Response(200, text=sse(frames(files, body)))
                if streaming
                else httpx.Response(200, json=body)
            ),
        ]
    )
    usage = await invoke(files, streaming)
    assert route.call_count == 2
    assert usage.cost_usd_ticks is None  # Existing response contract remains conservative.
    retry, success = metric_records(caplog, "xAI chat completion finished")
    assert retry["llmCallId"] == success["llmCallId"]
    assert (retry["attempt"], success["attempt"]) == (1, 2)
    assert retry["status"] == "RETRYING"
    assert "providerModel" not in retry
    assert not WORK_FIELDS & retry.keys()
    assert success["costUsdTicks"] == EXACT_TICKS
    assert success["providerUsageFinal"] is True


@pytest.mark.parametrize("files", [False, True])
async def test_timeout_distinguishes_partial_usage_from_terminal_usage(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture, files: bool
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)
    add_work_usage(body)
    event = (
        {"type": "response.created", "response": body}
        if files
        else {"model": body["model"], "choices": [], "usage": body["usage"]}
    )
    route = respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(
            200,
            stream=FailingAsyncByteStream(
                sse([event], done=False).encode(), httpx.ReadTimeout("PRIVATE-TIMEOUT")
            ),
        )
    )
    with pytest.raises(LlmBridgeError) as caught:
        await invoke(files, True)
    assert caught.value.category is ErrorCategory.TIMEOUT
    assert route.call_count == 1
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["providerModel"] == PROVIDER_MODEL
    assert record["status"] == "FAILED"
    if files:
        assert not WORK_FIELDS & record.keys()
        assert "providerUsageFinal" not in record
    else:
        assert record["costUsdTicks"] == EXACT_TICKS
        assert record["providerUsageFinal"] is False


@pytest.mark.parametrize("event_type", ["response.failed", "response.incomplete"])
async def test_provider_failure_retains_reported_terminal_work(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture, event_type: str
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(True)
    body["status"] = event_type.removeprefix("response.")
    add_work_usage(body)
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200, text=sse([{"type": event_type, "response": body}], done=False)
        )
    )
    with pytest.raises(LlmBridgeError) as caught:
        await invoke(True, True)
    assert caught.value.category is ErrorCategory.INTERNAL
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["status"] == "FAILED"
    assert record["providerModel"] == PROVIDER_MODEL
    assert record["costUsdTicks"] == EXACT_TICKS
    assert record["providerUsageFinal"] is True


@pytest.mark.parametrize("files", [False, True])
@pytest.mark.parametrize("status_code", [200, 500])
async def test_schema_and_http_errors_do_not_hide_billed_metadata(
    respx_mock: respx.MockRouter,
    caplog: pytest.LogCaptureFixture,
    files: bool,
    status_code: int,
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(files)
    add_work_usage(body)
    body["output" if files else "choices"] = []
    respx_mock.post(XAI_RESPONSES_URL if files else XAI_CHAT_COMPLETIONS_URL).mock(
        return_value=httpx.Response(status_code, json=body)
    )
    with pytest.raises(LlmBridgeError):
        await invoke(files, False)
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["errorCode"] == ("SCHEMA" if status_code == 200 else "INTERNAL")
    assert record["providerModel"] == PROVIDER_MODEL
    assert record["costUsdTicks"] == EXACT_TICKS
    assert record["numServerSideToolsUsed"] == 3
    assert record["providerUsageFinal"] is True


@pytest.mark.parametrize("bad_model", [None, True, 123, "", "private\ntext", "x" * 129, {}])
def test_unknown_model_is_not_filled_from_request_or_logged_as_arbitrary_text(
    bad_model: object,
) -> None:
    metrics = _CallMetrics.start(
        started_at=0, messages=[], profile=profile(), attachments=(), streaming=False
    )
    metrics.observe_response({"model": bad_model, "usage": None})
    assert metrics.fields()["providerModel"] is None
    assert metrics.fields()["providerUsageFinal"] is None
    metrics.observe_response({"model": PROVIDER_MODEL, "usage": {"cost_in_usd_ticks": EXACT_TICKS}})
    assert metrics.fields()["providerModel"] == PROVIDER_MODEL
    metrics.start_attempt()
    assert metrics.fields()["providerModel"] is None
    assert "costUsdTicks" not in metrics.fields()


@pytest.mark.parametrize("details", [None, "PRIVATE-DETAILS", [], True])
def test_bad_details_are_ignored_independently_of_cost(details: object) -> None:
    metrics = _CallMetrics.start(
        started_at=0, messages=[], profile=profile(), attachments=(), streaming=False
    )
    metrics.observe_response(
        {
            "usage": {
                "input_tokens": "bad",
                "server_side_tool_usage_details": details,
                "cost_in_usd_ticks": EXACT_TICKS,
            }
        },
        responses_api=True,
    )
    assert "serverSideToolUsageDetails" not in metrics.fields()
    assert "inputTokens" not in metrics.fields()
    assert metrics.fields()["costUsdTicks"] == EXACT_TICKS


def test_all_documented_call_counters_are_preserved_without_inventing_total() -> None:
    counts = {
        "web_search_calls": 1,
        "x_search_calls": 2,
        "code_interpreter_calls": 3,
        "file_search_calls": 4,
        "mcp_calls": 5,
        "document_search_calls": 6,
        "image_generation_calls": 7,
    }
    metrics = _CallMetrics.start(
        started_at=0, messages=[], profile=profile(), attachments=(), streaming=False
    )
    metrics.observe_response(
        {"usage": {"server_side_tool_usage_details": {**counts, "x_posts_fetched": 8}}}
    )
    assert metrics.fields()["serverSideToolUsageDetails"] == counts
    assert "numServerSideToolsUsed" not in metrics.fields()


async def test_invalid_stream_terminal_output_preserves_reported_work(
    respx_mock: respx.MockRouter, caplog: pytest.LogCaptureFixture
) -> None:
    caplog.set_level(logging.INFO, logger="edupilot_ai.llm.xai")
    body = provider_body(True)
    add_work_usage(body)
    body["output"] = []
    respx_mock.post(XAI_RESPONSES_URL).mock(
        return_value=httpx.Response(
            200, text=sse([{"type": "response.completed", "response": body}], done=False)
        )
    )
    with pytest.raises(LlmBridgeError) as caught:
        await invoke(True, True)
    assert caught.value.category is ErrorCategory.SCHEMA
    record = metric_records(caplog, "xAI chat completion finished")[0]
    assert record["status"] == "FAILED"
    assert record["costUsdTicks"] == EXACT_TICKS
    assert record["providerUsageFinal"] is True
