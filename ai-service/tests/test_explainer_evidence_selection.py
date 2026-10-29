"""Opt-in explanation evidence A/B plumbing; no live provider/quality claims."""

import json
import logging
from copy import deepcopy
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError, LlmTextDelta, LlmTextStreamCompleted, LlmUsage
from edupilot_ai.llm.xai import XaiLlmBridge
from edupilot_ai.models.plan import AgentOutput, ToolName
from edupilot_ai.models.quiz import QuizType
from edupilot_ai.models.turn import DetailLevel, TurnRequest
from edupilot_ai.orchestration.agents import ExplainerAgent
from edupilot_ai.orchestration.context import ContextBuilder, PlanContext
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation
from edupilot_ai.settings import Settings
from tests.fakes import FakeLlm
from tests.planner_fixtures import planner_output
from tests.test_llm_observability import metric_records
from tests.test_quiz_grading import make_quiz
from tests.test_turn_contract import make_explain_plan, make_plan
from tests.test_xai_llm_bridge import (
    completion_response,
    responses_response,
    responses_stream,
    stream_response,
)

_FILE = "file-reviewed-private"
_TEXT = "PRIVATE-PAGE-TEXT\n[그림 설명] PRIVATE-CAPTION"
_ANSWER = "PRIVATE-EXPLANATION"


def explain_payload(turn_payload: dict[str, object]) -> dict[str, Any]:
    payload: dict[str, Any] = deepcopy(turn_payload)
    payload["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "DETAILED"}}
    payload["context"].update(
        xaiFileId=_FILE,
        currentPageText=_TEXT,
        previousPageText="PRIVATE-PREVIOUS-PAGE",
        nextPageText="PRIVATE-NEXT-PAGE",
        learnerMemoryDigest="PRIVATE-LEARNER-MEMORY",
        pageQuizDecision={"pageNumber": 3, "suggestQuiz": False, "reason": "PRIVATE-DECISION"},
    )
    return payload


def enable_reviewed_page(settings: Settings) -> None:
    settings.edupilot_explainer_page_context_only_enabled = True
    settings.edupilot_explainer_page_context_only_pages = {_FILE: [3]}


def queue_explanation(fake_llm: FakeLlm, streaming: bool) -> None:
    usage = LlmUsage("grok-4.5", 20, 10, 3, 100)
    if streaming:
        fake_llm.queue_text_stream(_ANSWER, usage=usage)
    else:
        fake_llm.queue_completion(AgentOutput(markdown=_ANSWER), usage)


async def send(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    payload: dict[str, Any],
    streaming: bool,
) -> tuple[httpx.Response, dict[str, Any]]:
    headers = dict(auth_headers)
    if streaming:
        headers["Accept"] = "application/x-ndjson"
    response = await client.post("/internal/ai/turn", json=payload, headers=headers)
    if streaming and response.status_code == 200:
        events = [json.loads(line) for line in response.text.splitlines()]
        assert events[0] == {"type": "status", "stage": "PLANNING"}
        assert events[-1]["type"] == "completed"
        body = events[-1]["result"]
        assert "".join(e["text"] for e in events if e["type"] == "content_delta") == "".join(
            message["content"] for message in body["messages"]
        )
        assert all("usage" not in e for e in events[:-1])
        return response, body
    return response, response.json()


@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("suggest", [False, True])
async def test_ab_switch_changes_only_attachment_not_prompt_profile_or_contract(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    caplog: pytest.LogCaptureFixture,
    streaming: bool,
    suggest: bool,
) -> None:
    payload = explain_payload(turn_payload)
    payload["context"]["pageQuizDecision"]["suggestQuiz"] = suggest
    original = deepcopy(payload)
    settings.edupilot_explainer_page_context_only_pages = {_FILE: [3]}
    caplog.set_level(logging.INFO)
    bodies = []
    for enabled in (False, True):
        settings.edupilot_explainer_page_context_only_enabled = enabled
        queue_explanation(fake_llm, streaming)
        response, body = await send(client, auth_headers, payload, streaming)
        assert response.status_code == 200
        bodies.append(body)
    assert bodies[0] == bodies[1]
    assert payload == original
    assert bodies[1]["statePatch"] == {"pageStatus": "EXPLAINED"}
    assert bool(bodies[1]["uiActions"]) is suggest
    assert bodies[1]["usage"]["cost_usd_ticks"] == 100
    assert "planSource" not in bodies[1] and "evidenceMode" not in bodies[1]
    calls = fake_llm.stream_calls if streaming else fake_llm.calls
    attachments = fake_llm.stream_file_attachments if streaming else fake_llm.file_attachments
    assert len(calls) == 2  # One explanation each, no Planner, judge or automatic fallback call.
    assert [[item.file_id for item in items] for items in attachments] == [[_FILE], []]
    assert calls[0][:2] == calls[1][:2]
    assert calls[1][1] == settings.explainer_llm_profile
    data = json.loads(str(calls[1][0][1]["content"]))
    assert data["currentPageText"] == _TEXT
    assert data["previousPageText"] == original["context"]["previousPageText"]
    assert data["nextPageText"] == original["context"]["nextPageText"]
    assert data["learnerMemoryDigest"] == original["context"]["learnerMemoryDigest"]
    assert data["detailLevel"] == "DETAILED"
    assert "plan_source" not in data and "planSource" not in data
    records = metric_records(caplog, "explainer evidence selected")
    assert [r["evidenceMode"] for r in records] == ["PDF_ATTACHED", "PAGE_CONTEXT"]
    assert [r["evidenceSelectionReason"] for r in records] == ["DISABLED", "REVIEWED_PAGE_PLAN"]
    assert all(r["planSource"] == "DETERMINISTIC" for r in records)
    planning = metric_records(caplog, "turn planning finished")
    assert all(r["plannerAttempts"] == 0 for r in planning)
    for private in (_FILE, _TEXT, _ANSWER, "PRIVATE-DECISION", "PRIVATE-LEARNER-MEMORY"):
        assert private not in caplog.text
        assert private not in json.dumps([*records, *planning], ensure_ascii=False)


@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("case", ["no_review", "other_page", "replaced_pdf", "no_plan"])
async def test_flag_alone_never_removes_unreviewed_or_unplanned_pdf(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    streaming: bool,
    case: str,
) -> None:
    enable_reviewed_page(settings)
    payload = explain_payload(turn_payload)
    if case == "no_review":
        settings.edupilot_explainer_page_context_only_pages = {}
    elif case == "other_page":
        settings.edupilot_explainer_page_context_only_pages = {_FILE: [2]}
    elif case == "replaced_pdf":
        payload["context"]["xaiFileId"] = "file-replaced-private"
    else:
        payload["context"].pop("pageQuizDecision")
        fake_llm.queue(planner_output(make_explain_plan(propose_quiz=False)))
    queue_explanation(fake_llm, streaming)
    response, _ = await send(client, auth_headers, payload, streaming)
    assert response.status_code == 200
    attachments = fake_llm.stream_file_attachments if streaming else fake_llm.file_attachments
    assert attachments[-1][0].file_id == payload["context"]["xaiFileId"]
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == (2 if case == "no_plan" else 1)


@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize(
    ("context_key", "value", "signal"),
    [
        (
            "qaThreadDigest",
            {"threadRef": "thread-test", "digest": "PRIVATE-HISTORY"},
            "qaThreadPresent",
        ),
        (
            "recentMessages",
            [{"senderType": "USER", "content": "PRIVATE-QUESTION"}],
            "recentUserMessagePresent",
        ),
        ("quizAssessments", [{"weaknesses": ["PRIVATE-WEAKNESS"]}], "quizAssessmentsPresent"),
        ("pendingDiagnosis", {"diagnosisId": 1}, "pendingDiagnosisPresent"),
        ("latestRepair", "PRIVATE-REPAIR", "latestRepairPresent"),
        (
            "memory",
            {
                "temporaryCandidates": [
                    {
                        "candidateId": 1,
                        "type": "WEAKNESS",
                        "content": "PRIVATE-CANDIDATE",
                        "confidence": 0.8,
                        "evidenceRefs": [{"sourceType": "QA", "sourceId": 1, "sessionId": 100}],
                    }
                ]
            },
            "memoryCandidatesPresent",
        ),
    ],
)
async def test_adaptive_explanation_still_plans_and_keeps_pdf(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    caplog: pytest.LogCaptureFixture,
    streaming: bool,
    context_key: str,
    value: object,
    signal: str,
) -> None:
    enable_reviewed_page(settings)
    payload = explain_payload(turn_payload)
    payload["context"][context_key] = value
    fake_llm.queue(planner_output(make_explain_plan(propose_quiz=False)))
    queue_explanation(fake_llm, streaming)
    caplog.set_level(logging.INFO)
    response, _ = await send(client, auth_headers, payload, streaming)
    assert response.status_code == 200
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == 2
    assert fake_llm.file_attachments[0] == ()  # Planner is still text-only.
    attachments = fake_llm.stream_file_attachments if streaming else fake_llm.file_attachments
    assert attachments[-1][0].file_id == _FILE
    record = metric_records(caplog, "turn planning finished")[0]
    assert record["planSource"] == "LLM"
    assert record["explanationPlanningSignals"][signal] is True
    assert record["explanationPlanningSignals"]["pageQuizDecisionPresent"] is True
    selection = metric_records(caplog, "explainer evidence selected")[0]
    assert selection["evidenceSelectionReason"] == "NOT_DETERMINISTIC_EXPLANATION"
    assert "PRIVATE-" not in caplog.text


@pytest.mark.parametrize("event", ["USER_QUESTION", "QUIZ_TYPE_SELECTED"])
async def test_other_agents_keep_attachments_with_explainer_switch_enabled(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    event: str,
) -> None:
    enable_reviewed_page(settings)
    payload = explain_payload(turn_payload)
    if event == "USER_QUESTION":
        payload["event"] = {"eventType": event, "payload": {"message": "PRIVATE-QUESTION"}}
        fake_llm.queue(
            planner_output(
                make_plan(
                    ToolName.ANSWER_QUESTION,
                    {"qaThreadMode": "START_NEW", "threadRef": None},
                    "질문 답변",
                )
            )
        )
        fake_llm.queue(AgentOutput(markdown="질문 답변"))
    else:
        payload["event"] = {"eventType": event, "payload": {"quizType": "OX"}}
        fake_llm.queue(make_quiz(QuizType.OX))
    response, _ = await send(client, auth_headers, payload, False)
    assert response.status_code == 200
    assert fake_llm.file_attachments[-1][0].file_id == _FILE


@pytest.mark.parametrize("streaming", [False, True])
async def test_empty_page_still_uses_zero_llm_calls(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    streaming: bool,
) -> None:
    enable_reviewed_page(settings)
    payload = explain_payload(turn_payload)
    payload["context"]["currentPageText"] = " \n"
    response, body = await send(client, auth_headers, payload, streaming)
    assert response.status_code == 200
    assert "텍스트 내용이 없어요" in body["messages"][0]["content"]
    assert not fake_llm.calls and not fake_llm.stream_calls


async def test_unproven_agent_context_keeps_pdf(
    settings: Settings, fake_llm: FakeLlm, turn_payload: dict[str, object]
) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(explain_payload(turn_payload)))
    assert context.plan_source is None
    agent = ExplainerAgent(
        llm=fake_llm,
        profile=settings.explainer_llm_profile,
        page_context_only_enabled=True,
        page_context_only_pages={_FILE: [3]},
    )
    fake_llm.queue(AgentOutput(markdown=_ANSWER))
    await agent.run(context, DetailLevel.NORMAL, timeout_seconds=30)
    assert fake_llm.file_attachments[0][0].file_id == _FILE
    assert "planSource" not in PlanContext.from_agent_context(context).model_dump(by_alias=True)


async def test_internal_plan_source_cannot_be_supplied_on_the_wire(
    client: httpx.AsyncClient, auth_headers: dict[str, str], turn_payload: dict[str, object]
) -> None:
    payload = explain_payload(turn_payload)
    payload["context"]["planSource"] = "DETERMINISTIC"
    response = await client.post("/internal/ai/turn", json=payload, headers=auth_headers)
    assert response.status_code == 422


async def test_evidence_switch_does_not_bypass_policy(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reject(*args: object, **kwargs: object) -> None:
        raise PolicyViolation("test rejection")

    enable_reviewed_page(settings)
    monkeypatch.setattr(PolicyVerifier, "verify", reject)
    response = await client.post(
        "/internal/ai/turn", json=explain_payload(turn_payload), headers=auth_headers
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "AI_POLICY_REJECTED"
    assert not fake_llm.calls and not fake_llm.stream_calls


@pytest.mark.parametrize("streaming", [False, True])
async def test_llm_failure_does_not_trigger_hidden_pdf_fallback_call(
    client: httpx.AsyncClient,
    settings: Settings,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    streaming: bool,
) -> None:
    enable_reviewed_page(settings)
    error = LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True)
    headers = dict(auth_headers)
    if streaming:
        headers["Accept"] = "application/x-ndjson"
        fake_llm.queue_text_stream(error)
    else:
        fake_llm.queue(error)
    response = await client.post(
        "/internal/ai/turn", json=explain_payload(turn_payload), headers=headers
    )
    if streaming:
        events = [json.loads(line) for line in response.text.splitlines()]
        assert events[-1]["type"] == "error"
        assert events[-1]["code"] == "AI_SERVICE_TIMEOUT"
        assert not any(e["type"] == "completed" for e in events)
    else:
        assert response.status_code == 504
        assert response.json()["error"]["code"] == "AI_SERVICE_TIMEOUT"
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == 1


def test_settings_default_off_and_env_json(
    settings: Settings, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert not settings.edupilot_explainer_page_context_only_enabled
    assert settings.edupilot_explainer_page_context_only_pages == {}
    monkeypatch.setenv("EDUPILOT_EXPLAINER_PAGE_CONTEXT_ONLY_ENABLED", "true")
    monkeypatch.setenv("EDUPILOT_EXPLAINER_PAGE_CONTEXT_ONLY_PAGES", json.dumps({_FILE: [2, 3]}))
    loaded = Settings(
        _env_file=None,
        edupilot_internal_token=settings.edupilot_internal_token,
        xai_api_key=settings.xai_api_key,
    )
    assert loaded.edupilot_explainer_page_context_only_enabled
    assert loaded.edupilot_explainer_page_context_only_pages == {_FILE: [2, 3]}


@pytest.mark.parametrize("pages", [{" ": [3]}, {_FILE: [0]}, {_FILE: [-1]}, {_FILE: [1.5]}])
def test_invalid_review_configuration_is_rejected(settings: Settings, pages: object) -> None:
    data = settings.model_dump()
    data["edupilot_explainer_page_context_only_pages"] = pages
    with pytest.raises(ValidationError):
        Settings.model_validate(data)


@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("cache_enabled", [False, True])
async def test_real_bridge_routes_both_evidence_modes_with_mock_transport(
    settings: Settings,
    turn_payload: dict[str, object],
    streaming: bool,
    cache_enabled: bool,
) -> None:
    requests: list[httpx.Request] = []

    def provider(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        with_file = request.url.path == "/v1/responses"
        assert with_file or request.url.path == "/v1/chat/completions"
        if streaming:
            return httpx.Response(
                200,
                text=responses_stream() if with_file else stream_response(),
                headers={"Content-Type": "text/event-stream"},
            )
        content = json.dumps({"markdown": _ANSWER})
        return httpx.Response(
            200,
            json=(responses_response if with_file else completion_response)(content=content),
        )

    context = ContextBuilder().build(TurnRequest.model_validate(explain_payload(turn_payload)))
    context = context.model_copy(update={"plan_source": "DETERMINISTIC"})
    async with httpx.AsyncClient(transport=httpx.MockTransport(provider)) as http:
        bridge = XaiLlmBridge(
            client=http,
            api_key=settings.xai_api_key,
            prompt_cache_layout_enabled=cache_enabled,
            prompt_cache_routing_enabled=cache_enabled,
        )
        for enabled in (False, True):
            agent = ExplainerAgent(
                llm=bridge,
                profile=settings.explainer_llm_profile,
                page_context_only_enabled=enabled,
                page_context_only_pages={_FILE: [3]},
            )
            if streaming:
                stream = agent.stream(context, DetailLevel.NORMAL, timeout_seconds=30)
                items = [item async for item in stream.items]
                assert isinstance(items[-1], LlmTextStreamCompleted)
                assert any(isinstance(item, LlmTextDelta) for item in items)
            else:
                result = await agent.run(context, DetailLevel.NORMAL, timeout_seconds=30)
                assert result.message is not None and result.message.content == _ANSWER
    assert [request.url.path for request in requests] == ["/v1/responses", "/v1/chat/completions"]
    baseline, candidate = [json.loads(request.content) for request in requests]
    assert _FILE in json.dumps(baseline) and _FILE not in json.dumps(candidate)
    assert baseline["model"] == candidate["model"] == settings.explainer_llm_profile.model
    assert baseline["max_output_tokens"] == candidate["max_tokens"] == settings.agent_max_tokens
    assert baseline["reasoning"]["effort"] == candidate["reasoning_effort"] == "medium"
