"""Opt-in page plans and adaptive QA proposals, without additional LLM calls."""

import json
from copy import deepcopy
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from edupilot_ai.models.outline import OutlineRequest, PlannedOutlineOutput
from edupilot_ai.models.plan import AgentOutput, PlanAction, ToolName, TurnPlan
from edupilot_ai.models.quiz import QuizGeneration, QuizType
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.context import ContextBuilder, PlanContext
from edupilot_ai.orchestration.plan_synthesis import synthesize_plan
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation
from edupilot_ai.orchestration.prompts import plan_messages
from edupilot_ai.orchestration.quiz_output import quiz_output_model
from edupilot_ai.outline.service import OutlineValidationError, validate_outline_output
from tests.fakes import FakeLlm
from tests.planner_fixtures import planner_output
from tests.test_outline import outline_output, outline_payload
from tests.test_quiz_grading import add_section_quiz_context, make_quiz
from tests.test_turn_contract import make_explain_plan, make_plan


def planned_output() -> PlannedOutlineOutput:
    raw = outline_output().model_dump(mode="json", by_alias=True)
    # Page 1 is inside section 1, not a section boundary. False is not a missing plan.
    raw["pageQuizPlan"] = [
        {"pageNumber": 1, "suggestQuiz": True, "reason": "핵심 가정을 독립적으로 점검"},
        {"pageNumber": 2, "suggestQuiz": False, "reason": "앞 개념의 추가 예시"},
        {"pageNumber": 3, "suggestQuiz": True, "reason": "클래스 개념을 점검"},
    ]
    return PlannedOutlineOutput.model_validate(raw)


def qa_quiz_plan(*, follow_up: bool = False) -> TurnPlan:
    plan = make_plan(
        ToolName.ANSWER_QUESTION,
        {"qaThreadMode": "FOLLOW_UP" if follow_up else "START_NEW", "threadRef": None},
        "질문에 답하고 필요하면 확인 문제 제안",
    )
    plan.actions.append(make_explain_plan(propose_quiz=True).actions[1])
    plan.pedagogy_policy.intervention_budget = 2
    return plan


def raw_turn(turn_payload: dict[str, object]) -> dict[str, Any]:
    payload = deepcopy(turn_payload)
    payload["capabilities"] = {}
    return payload


async def test_opt_in_outline_returns_every_page_and_mid_section_candidate(
    client: httpx.AsyncClient, fake_llm: FakeLlm, auth_headers: dict[str, str]
) -> None:
    fake_llm.queue(planned_output())
    payload = outline_payload()
    payload["includePageQuizPlan"] = True
    response = await client.post("/internal/ai/outline", json=payload, headers=auth_headers)
    assert response.status_code == 200
    assert [item["pageNumber"] for item in response.json()["pageQuizPlan"]] == [1, 2, 3]
    assert response.json()["pageQuizPlan"][0]["suggestQuiz"] is True
    assert len(fake_llm.calls) == 1
    prompt = fake_llm.calls[0][0][0]["content"]
    assert "섹션 경계·quizCheckpoints·텍스트 글자 수로 제한하지 마라" in prompt


@pytest.mark.parametrize("numbers", [[1, 3], [1, 1, 3], [3, 2, 1], [1, 2, 4]])
def test_page_plan_must_cover_every_page_once_in_order(numbers: list[int]) -> None:
    output = planned_output()
    output.page_quiz_plan = [
        output.page_quiz_plan[0].model_copy(update={"page_number": number}) for number in numbers
    ]
    request = OutlineRequest.model_validate({**outline_payload(), "includePageQuizPlan": True})
    with pytest.raises(OutlineValidationError, match="PAGE_QUIZ_PLAN_COVERAGE_INVALID"):
        validate_outline_output(request, output)


async def test_invalid_page_plan_regenerates_once_with_reason(
    client: httpx.AsyncClient, fake_llm: FakeLlm, auth_headers: dict[str, str]
) -> None:
    bad = planned_output()
    bad.page_quiz_plan.pop()
    fake_llm.queue(bad, planned_output())
    response = await client.post(
        "/internal/ai/outline",
        json={**outline_payload(), "includePageQuizPlan": True},
        headers=auth_headers,
    )
    assert response.status_code == 200
    assert len(fake_llm.calls) == 2
    assert "PAGE_QUIZ_PLAN_COVERAGE_INVALID" in fake_llm.calls[1][0][0]["content"]


@pytest.mark.parametrize("suggest", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
async def test_preplanned_explanation_uses_one_call_and_preserves_policy_and_stream(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    suggest: bool,
    streaming: bool,
) -> None:
    payload = raw_turn(turn_payload)
    payload["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "NORMAL"}}
    payload["context"]["xaiFileId"] = "file-plan"
    payload["context"]["pageQuizDecision"] = {
        "pageNumber": 3,
        "suggestQuiz": suggest,
        "reason": "자료 기준 점검 판단",
    }
    context = ContextBuilder().build(TurnRequest.model_validate(payload))
    plan = synthesize_plan(context)
    assert plan is not None
    verified, _ = PolicyVerifier().verify(plan, context)
    assert len(verified.actions) == (2 if suggest else 1)
    headers = dict(auth_headers)
    if streaming:
        headers["Accept"] = "application/x-ndjson"
        fake_llm.queue_text_stream("설명 ", "본문")
    else:
        fake_llm.queue(AgentOutput(markdown="설명 본문"))
    response = await client.post("/internal/ai/turn", json=payload, headers=headers)
    assert response.status_code == 200
    if streaming:
        events = [json.loads(line) for line in response.text.splitlines()]
        assert events[0] == {"type": "status", "stage": "PLANNING"}
        assert events[-1]["type"] == "completed"
        body = events[-1]["result"]
        assert "".join(e["text"] for e in events if e["type"] == "content_delta") == "설명 본문"
    else:
        body = response.json()
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == 1
    assert body["statePatch"]["pageStatus"] == "EXPLAINED"
    assert bool(body["uiActions"]) is suggest


def test_old_material_and_adaptive_signals_keep_runtime_planner(
    turn_payload: dict[str, object],
) -> None:
    payload = raw_turn(turn_payload)
    payload["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "NORMAL"}}
    payload["context"]["xaiFileId"] = "file-plan"
    assert synthesize_plan(ContextBuilder().build(TurnRequest.model_validate(payload))) is None
    payload["context"]["pageQuizDecision"] = {
        "pageNumber": 3,
        "suggestQuiz": False,
        "reason": "복습 불필요",
    }
    payload["context"]["quizAssessments"] = [{"weaknesses": ["선형 가정 혼동"]}]
    assert synthesize_plan(ContextBuilder().build(TurnRequest.model_validate(payload))) is None


@pytest.mark.parametrize("follow_up", [False, True])
@pytest.mark.parametrize("streaming", [False, True])
async def test_qa_can_propose_even_when_page_default_is_false_without_extra_call(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    follow_up: bool,
    streaming: bool,
) -> None:
    payload = raw_turn(turn_payload)
    payload["capabilities"]["qaQuizProposal"] = True
    payload["context"]["pageQuizDecision"] = {
        "pageNumber": 3,
        "suggestQuiz": False,
        "reason": "기본 제안 없음",
    }
    if follow_up:
        payload["context"]["qaThreadDigest"] = {"threadRef": "qa-test", "digest": "같은 개념 질문"}
    plan = qa_quiz_plan(follow_up=follow_up)
    plan.propose_note = True  # Quiz takes precedence if the model also proposes a note.
    fake_llm.queue(planner_output(plan))
    headers = dict(auth_headers)
    if streaming:
        headers["Accept"] = "application/x-ndjson"
        fake_llm.queue_text_stream("질문 답변")
    else:
        fake_llm.queue(AgentOutput(markdown="질문 답변"))
    response = await client.post("/internal/ai/turn", json=payload, headers=headers)
    assert response.status_code == 200
    body = json.loads(response.text.splitlines()[-1])["result"] if streaming else response.json()
    assert body["messages"][0]["messageType"] == "QA"
    assert body["statePatch"]["qaThread"]["mode"] == ("FOLLOW_UP" if follow_up else "START_NEW")
    assert len(body["uiActions"]) == 1
    assert body["uiActions"][0]["yesEvent"] == "SHOW_QUIZ_TYPE_SELECT"
    assert "quiz" not in body
    assert len(fake_llm.calls) + len(fake_llm.stream_calls) == 2
    assert not fake_llm.file_attachments[0]  # No extra whole-document Planner input.
    assert "never generate a quiz in this turn" in fake_llm.calls[0][0][0]["content"]


def test_qa_proposal_is_opt_in_and_requires_page_evidence(turn_payload: dict[str, object]) -> None:
    request = TurnRequest.model_validate(turn_payload)
    with pytest.raises(PolicyViolation):
        PolicyVerifier().verify(qa_quiz_plan(), ContextBuilder().build(request))
    request.capabilities.qa_quiz_proposal = True
    request.event.payload.include_current_page = False
    with pytest.raises(PolicyViolation, match="current page evidence"):
        PolicyVerifier().verify(qa_quiz_plan(), ContextBuilder().build(request))


@pytest.mark.parametrize(
    "tools",
    [
        [ToolName.PROMPT_BINARY_DECISION, ToolName.ANSWER_QUESTION],
        [
            ToolName.ANSWER_QUESTION,
            ToolName.PROMPT_BINARY_DECISION,
            ToolName.PROMPT_BINARY_DECISION,
        ],
        [ToolName.ANSWER_QUESTION, ToolName.GENERATE_QUIZ_OX],
    ],
)
def test_qa_rejects_wrong_order_duplicate_proposal_or_auto_generation(
    turn_payload: dict[str, object], tools: list[ToolName]
) -> None:
    request = TurnRequest.model_validate(turn_payload)
    request.capabilities.qa_quiz_proposal = True
    plan = qa_quiz_plan()
    plan.actions = [
        PlanAction(action_id=str(i), tool=tool, args={}) for i, tool in enumerate(tools)
    ]
    with pytest.raises(PolicyViolation, match="invalid action sequence"):
        PolicyVerifier().verify(plan, ContextBuilder().build(request))


def test_mismatched_page_plan_is_rejected_and_missing_capability_stays_forbidden(
    turn_payload: dict[str, object],
) -> None:
    payload = raw_turn(turn_payload)
    payload["context"]["pageQuizDecision"] = {
        "pageNumber": 99,
        "suggestQuiz": True,
        "reason": "다른 페이지",
    }
    with pytest.raises(ValidationError, match="pageQuizDecision"):
        TurnRequest.model_validate(payload)
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    assert (
        "PROMPT_BINARY_DECISION is forbidden"
        in plan_messages(PlanContext.from_agent_context(context), retry=False)[0]["content"]
    )


@pytest.mark.parametrize("quiz_type", list(QuizType))
def test_live_quiz_generation_is_exactly_five_without_changing_shared_contract(
    quiz_type: QuizType,
) -> None:
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    extra = deepcopy(raw["questions"][0])
    extra["questionId"] = "sixth"
    raw["questions"].append(extra)
    raw["questionCount"] = 6
    assert QuizGeneration.model_validate(raw).question_count == 6  # old stored artifacts
    with pytest.raises(ValidationError):
        quiz_output_model(quiz_type).model_validate(raw)


@pytest.mark.parametrize("file_id", [None, "file-plan"])
async def test_adaptive_explanation_keeps_decisions_without_reanalyzing_pdf(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    file_id: str | None,
) -> None:
    payload = raw_turn(turn_payload)
    payload["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "NORMAL"}}
    payload["context"].update(
        xaiFileId=file_id,
        pageQuizDecision={"pageNumber": 3, "suggestQuiz": False, "reason": "기본 제안 없음"},
        quizAssessments=[{"weaknesses": ["개념을 혼동함"]}],
    )
    fake_llm.queue(
        planner_output(make_explain_plan(propose_quiz=True)), AgentOutput(markdown="설명")
    )
    response = await client.post("/internal/ai/turn", json=payload, headers=auth_headers)
    assert response.status_code == 200
    assert response.json()["uiActions"][0]["yesEvent"] == "SHOW_QUIZ_TYPE_SELECT"
    assert len(fake_llm.calls) == 2
    assert fake_llm.file_attachments[0] == ()
    assert bool(fake_llm.file_attachments[1]) is (file_id is not None)
    assert "Do not require a PDF attachment" in fake_llm.calls[0][0][0]["content"]


def test_conversation_memory_decisions_are_not_silently_skipped(
    turn_payload: dict[str, object],
) -> None:
    payload = raw_turn(turn_payload)
    payload["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "NORMAL"}}
    payload["context"]["pageQuizDecision"] = {
        "pageNumber": 3,
        "suggestQuiz": True,
        "reason": "핵심 개념",
    }
    payload["context"]["recentMessages"] = [{"senderType": "USER", "content": "그림 예시가 좋아요"}]
    assert synthesize_plan(ContextBuilder().build(TurnRequest.model_validate(payload))) is None


def test_preplanned_reexplanation_does_not_repeat_the_default_proposal(
    turn_payload: dict[str, object],
) -> None:
    payload = raw_turn(turn_payload)
    payload["event"] = {"eventType": "EXPLAIN_CURRENT_PAGE", "payload": {"detailLevel": "NORMAL"}}
    payload["session"]["pageStatus"] = "EXPLAINED"
    payload["context"]["pageQuizDecision"] = {
        "pageNumber": 3,
        "suggestQuiz": True,
        "reason": "핵심 개념",
    }
    context = ContextBuilder().build(TurnRequest.model_validate(payload))
    plan = synthesize_plan(context)
    assert plan is not None and len(plan.actions) == 1
    PolicyVerifier().verify(plan, context)
    with pytest.raises(PolicyViolation, match="re-explanation"):
        PolicyVerifier().verify(make_explain_plan(propose_quiz=True), context)


async def test_consented_qa_focus_reaches_quiz_without_expanding_coverage(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = raw_turn(turn_payload)
    add_section_quiz_context(payload)
    payload["context"]["quizContext"]["learningFocus"] = "기울기의 부호 해석"
    quiz = make_quiz(QuizType.MCQ)
    quiz.coverage.start_page = 1
    fake_llm.queue(quiz)
    response = await client.post("/internal/ai/turn", json=payload, headers=auth_headers)
    assert response.status_code == 200
    messages = fake_llm.calls[0][0]
    data = json.loads(messages[1]["content"])
    assert data["learningFocus"] == "기울기의 부호 해석"
    assert response.json()["quiz"]["coverage"] == {"startPage": 1, "endPage": 3}
    assert "자료 근거 밖으로 출제 범위를 넓히지 마라" in messages[0]["content"]


async def test_legacy_outline_omits_new_output_and_full_page_opt_in_is_validated(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(outline_output())
    response = await client.post(
        "/internal/ai/outline", json=outline_payload(), headers=auth_headers
    )
    assert response.status_code == 200 and "pageQuizPlan" not in response.json()
    bad = {**outline_payload(), "includePageQuizPlan": True, "totalPages": 999999999}
    response = await client.post("/internal/ai/outline", json=bad, headers=auth_headers)
    assert response.status_code == 422 and len(fake_llm.calls) == 1


def test_legacy_planner_payload_has_no_capability_fields(turn_payload: dict[str, object]) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    data = json.loads(
        plan_messages(PlanContext.from_agent_context(context), retry=False)[1]["content"]
    )
    assert "pageQuizDecision" not in data and "qaQuizProposalEnabled" not in data
