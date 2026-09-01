"""Deterministic Plan synthesis and policy safety-net tests."""

from copy import deepcopy

import pytest

from edupilot_ai.models.plan import ToolName
from edupilot_ai.models.quiz import QuizType
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.context import AgentContext, ContextBuilder
from edupilot_ai.orchestration.plan_synthesis import synthesize_plan
from edupilot_ai.orchestration.policy import PolicyVerifier


def _context(
    turn_payload: dict[str, object],
    *,
    event_type: str,
    event_payload: dict[str, object],
) -> AgentContext:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": event_type,
        "payload": event_payload,
    }
    return ContextBuilder().build(TurnRequest.model_validate(payload))


def test_explain_plan_requires_runtime_orchestrator_when_pdf_is_attached(
    turn_payload: dict[str, object],
) -> None:
    context = _context(
        turn_payload,
        event_type="EXPLAIN_CURRENT_PAGE",
        event_payload={"detailLevel": "DETAILED"},
    )
    context = context.model_copy(update={"xai_file_id": "file-runtime-plan"})

    plan = synthesize_plan(context)

    assert plan is None


def test_explain_plan_without_pdf_keeps_legacy_deterministic_fallback(
    turn_payload: dict[str, object],
) -> None:
    context = _context(
        turn_payload,
        event_type="EXPLAIN_CURRENT_PAGE",
        event_payload={"detailLevel": "DETAILED"},
    )

    plan = synthesize_plan(context)

    assert plan is not None
    assert plan.pedagogy_policy.intervention_budget == 1
    assert plan.actions[0].tool is ToolName.EXPLAIN_PAGE
    assert plan.actions[0].args == {"page": 3, "detailLevel": "DETAILED"}
    verified, adjustments = PolicyVerifier().verify(plan, context)
    assert verified == plan
    assert adjustments == []


@pytest.mark.parametrize("quiz_type", list(QuizType))
def test_quiz_plan_maps_selected_type_to_exact_tool(
    turn_payload: dict[str, object],
    quiz_type: QuizType,
) -> None:
    context = _context(
        turn_payload,
        event_type="QUIZ_TYPE_SELECTED",
        event_payload={"quizType": quiz_type.value},
    )

    plan = synthesize_plan(context)

    assert plan is not None
    assert plan.actions[0].tool is ToolName(f"GENERATE_QUIZ_{quiz_type.value}")
    assert plan.actions[0].args == {"quizType": quiz_type.value}
    verified, adjustments = PolicyVerifier().verify(plan, context)
    assert verified == plan
    assert adjustments == []


def test_fixed_qa_guidance_preserves_existing_thread_ref(
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context_payload = payload["context"]
    assert isinstance(context_payload, dict)
    context_payload["qaThreadDigest"] = {"threadRef": "qa-11", "digest": "편차 질문"}
    payload["event"] = {
        "eventType": "USER_QUESTION",
        "payload": {"message": "다음 페이지 설명해줘"},
    }
    context = ContextBuilder().build(TurnRequest.model_validate(payload))

    plan = synthesize_plan(context)

    assert plan is not None
    assert plan.actions[0].args == {
        "qaThreadMode": "FOLLOW_UP",
        "threadRef": "qa-11",
    }
    PolicyVerifier().verify(plan, context)


def test_general_or_page_detached_question_still_requires_planner(
    turn_payload: dict[str, object],
) -> None:
    general = _context(
        turn_payload,
        event_type="USER_QUESTION",
        event_payload={"message": "편차가 무엇인지 설명해줘"},
    )
    detached_payload = deepcopy(turn_payload)
    detached_context = detached_payload["context"]
    assert isinstance(detached_context, dict)
    detached_context["currentPageText"] = None
    detached_payload["event"] = {
        "eventType": "USER_QUESTION",
        "payload": {
            "message": "HTTP와 HTTPS의 차이는 뭐야?",
            "includeCurrentPage": False,
        },
    }
    detached = ContextBuilder().build(TurnRequest.model_validate(detached_payload))

    assert synthesize_plan(general) is None
    assert synthesize_plan(detached) is None
