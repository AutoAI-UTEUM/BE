"""POST /internal/ai/turn orchestration contract."""

import logging
from copy import deepcopy

import httpx
import pytest

from edupilot_ai.core.errors import ErrorCategory, InternalErrorResponse
from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.plan import (
    AgentOutput,
    PedagogyPolicy,
    PlanAction,
    ToolName,
    TurnPlan,
)
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.agents import ExplainerAgent, QaAgent
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.dispatcher import ToolDispatcher, merge_state_patch
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation
from edupilot_ai.orchestration.prompts import plan_messages
from edupilot_ai.orchestration.timing import TurnDeadline
from edupilot_ai.settings import Settings
from tests.fakes import FakeLlm


def make_plan(tool: ToolName, args: dict[str, object], goal: str) -> TurnPlan:
    return TurnPlan(
        turn_goal=goal,
        pedagogy_policy=PedagogyPolicy(
            mode="GROUND_FIRST",
            reason="contract test",
            allow_direct_answer=True,
            hint_depth="MEDIUM",
            intervention_budget=1,
        ),
        actions=[PlanAction(action_id="action-1", tool=tool, args=args)],
        reason="contract test plan",
    )


async def post_turn(
    client: httpx.AsyncClient,
    headers: dict[str, str],
    payload: dict[str, object],
) -> httpx.Response:
    return await client.post("/internal/ai/turn", headers=headers, json=payload)


async def test_explain_current_page_turn(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "DETAILED"},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["learnerConfidence"] = "HIGH"
    fake_llm.queue(
        make_plan(
            ToolName.EXPLAIN_PAGE,
            {"page": 3, "detailLevel": "DETAILED"},
            "EXPLAIN_CURRENT_PAGE",
        ),
        AgentOutput(markdown="상세한 현재 페이지 설명", thought_summary="페이지 설명"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    turn = TurnResponse.model_validate(response.json())
    assert turn.turn_goal == "EXPLAIN_CURRENT_PAGE"
    assert turn.messages[0].message_type == "EXPLANATION"
    assert turn.state_patch == {"pageStatus": "EXPLAINED"}
    assert turn.actions_executed[0].agent == "ExplainerAgent"
    assert "adjustments" not in response.json()["actionsExecuted"][0]
    assert len(fake_llm.calls) == 2
    assert '"learner_confidence":"HIGH"' in fake_llm.calls[0][0][1]["content"]
    assert '"learnerConfidence": "HIGH"' in fake_llm.calls[1][0][1]["content"]
    assert "learnerMemoryDigest" in fake_llm.calls[1][0][1]["content"]
    assert "모든 학습자 대상 텍스트" in fake_llm.calls[1][0][0]["content"]


def test_plan_prompt_declares_policy_value_contracts(
    turn_payload: dict[str, object],
) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))

    system_prompt = plan_messages(context, retry=False)[0]["content"]

    assert "qaThreadMode must be exactly START_NEW or FOLLOW_UP" in system_prompt
    assert "START_NEW requires threadRef=null" in system_prompt
    assert "FOLLOW_UP requires the exact snapshot qaThreadDigest.threadRef" in system_prompt
    assert "if qaThreadDigest is absent, choose START_NEW" in system_prompt
    assert "quizType must equal the event payload value" in system_prompt
    assert "one of MCQ, OX, SHORT, ESSAY" in system_prompt
    assert "diagnosisId must equal snapshot pendingDiagnosis.diagnosisId" in system_prompt
    assert "type must be one of STRENGTH, WEAKNESS, MISCONCEPTION, PREFERENCE" in (
        system_prompt
    )
    assert "confidence must be a number from 0 to 1" in system_prompt


@pytest.mark.parametrize(
    ("plan_args", "field", "from_value", "to_value", "reason"),
    [
        (
            {"page": 2, "detailLevel": "DETAILED"},
            "page",
            2,
            3,
            "PAGE_MISMATCH_CORRECTED",
        ),
        (
            {"page": 3, "detailLevel": "NORMAL"},
            "detailLevel",
            "NORMAL",
            "DETAILED",
            "EVENT_PAYLOAD_MISMATCH_CORRECTED",
        ),
    ],
)
async def test_explain_policy_records_adjustment(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    plan_args: dict[str, object],
    field: str,
    from_value: object,
    to_value: object,
    reason: str,
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "DETAILED"},
    }
    fake_llm.queue(
        make_plan(ToolName.EXPLAIN_PAGE, plan_args, "EXPLAIN_CURRENT_PAGE"),
        AgentOutput(markdown="보정된 현재 페이지 설명", thought_summary="페이지 설명"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    assert response.json()["actionsExecuted"][0]["adjustments"] == [
        {
            "field": field,
            "from": from_value,
            "to": to_value,
            "reason": reason,
        }
    ]


async def test_explain_policy_normalizes_page_number_alias(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "NORMAL"},
    }
    fake_llm.queue(
        make_plan(
            ToolName.EXPLAIN_PAGE,
            {"pageNumber": 1, "detailLevel": "NORMAL"},
            "EXPLAIN_CURRENT_PAGE",
        ),
        AgentOutput(markdown="별칭 보정 후 설명", thought_summary="페이지 설명"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    assert response.json()["actionsExecuted"][0]["adjustments"] == [
        {
            "field": "page",
            "from": 1,
            "to": 3,
            "reason": "PAGE_MISMATCH_CORRECTED",
        }
    ]


async def test_explain_policy_still_rejects_missing_required_key(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "NORMAL"},
    }
    fake_llm.queue(
        make_plan(
            ToolName.EXPLAIN_PAGE,
            {"detailLevel": "NORMAL"},
            "EXPLAIN_CURRENT_PAGE",
        )
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 502
    assert response.json()["error"]["category"] == "POLICY"
    assert len(fake_llm.calls) == 1


async def test_policy_rejection_logs_reason_and_plan_actions(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    caplog: pytest.LogCaptureFixture,
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "NORMAL"},
    }
    fake_llm.queue(
        make_plan(
            ToolName.EXPLAIN_PAGE,
            {"detailLevel": "NORMAL"},
            "EXPLAIN_CURRENT_PAGE",
        )
    )

    with caplog.at_level(
        logging.WARNING,
        logger="edupilot_ai.orchestration.service",
    ):
        response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 502
    assert "tool args do not match policy" in caplog.text
    assert "EXPLAIN_PAGE" in caplog.text
    assert "detailLevel" in caplog.text


async def test_user_question_start_new(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["learnerConfidence"] = "LOW"
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        ),
        AgentOutput(markdown="편차는 평균에서 떨어진 정도입니다.", thought_summary="근거 연결"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    turn = TurnResponse.model_validate(response.json())
    assert turn.messages[0].message_type == "QA"
    assert turn.state_patch == {"qaThread": {"mode": "START_NEW"}}
    assert '"qaThreadDigest": null' in fake_llm.calls[1][0][1]["content"]
    assert '"learnerConfidence": "LOW"' in fake_llm.calls[1][0][1]["content"]
    assert "모든 학습자 대상 텍스트" in fake_llm.calls[1][0][0]["content"]


@pytest.mark.parametrize("mode_alias", ["NEW", "new"])
async def test_user_question_new_alias_strips_invented_thread_ref(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    mode_alias: str,
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": mode_alias, "threadRef": "qa-invented"},
            "ANSWER_USER_QUESTION",
        ),
        AgentOutput(markdown="편차는 평균과의 차이입니다.", thought_summary="근거 연결"),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 200
    turn = TurnResponse.model_validate(response.json())
    assert turn.state_patch == {"qaThread": {"mode": "START_NEW"}}
    assert len(fake_llm.calls) == 2


async def test_user_question_follow_up_includes_thread_and_latest_repair(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["qaThreadDigest"] = {"threadRef": "qa-7", "summary": "편차 질문"}
    context["latestRepair"] = {"content": "평균부터 다시 설명"}
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "FOLLOW_UP", "threadRef": "qa-7"},
            "ANSWER_FOLLOW_UP",
        ),
        AgentOutput(markdown="앞선 설명과 연결하면...", thought_summary="후속 연결"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    turn = TurnResponse.model_validate(response.json())
    assert turn.state_patch == {"qaThread": {"mode": "FOLLOW_UP", "threadRef": "qa-7"}}
    agent_prompt = fake_llm.calls[1][0][1]["content"]
    assert "편차 질문" in agent_prompt
    assert "평균부터 다시 설명" in agent_prompt


async def test_qa_insufficient_evidence_does_not_call_agent_llm(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["currentPageText"] = ""
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        )
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    assert response.json()["messages"][0]["content"] == (
        "제공된 강의 자료만으로는 이 질문에 답하기 어렵습니다. "
        "현재 페이지와 관련된 질문으로 다시 물어봐 주세요."
    )
    assert len(fake_llm.calls) == 1


async def test_pipeline_tool_is_rejected_by_policy(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(ToolName.GRADE_OPEN_RESPONSE, {}, "INVALID_PIPELINE_TOOL")
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_POLICY_REJECTED"
    assert error.error.category == "POLICY"


async def test_plan_schema_failure_regenerates_once(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        ),
        AgentOutput(markdown="재생성 후 답변", thought_summary="재생성"),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 200
    assert len(fake_llm.calls) == 3
    assert "regenerate exactly once" in fake_llm.calls[1][0][0]["content"]


async def test_plan_schema_failure_twice_returns_schema_envelope(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_RESPONSE_INVALID"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False


async def test_agent_timeout_returns_retryable_timeout_envelope(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        ),
        LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 504
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_SERVICE_TIMEOUT"
    assert error.error.category == "TIMEOUT"
    assert error.error.retryable is True


async def test_turn_aggregates_plan_and_agent_usage(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue_completion(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        ),
        LlmUsage("grok-4.5", 10, 4, 2),
    )
    fake_llm.queue_completion(
        AgentOutput(markdown="usage answer", thought_summary="usage"),
        LlmUsage("grok-4.5", 5, 8, 3),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 200
    assert response.json()["usage"] == {
        "model": "grok-4.5",
        "inputTokens": 15,
        "outputTokens": 12,
        "reasoningTokens": 5,
    }


async def test_follow_up_without_digest_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "FOLLOW_UP", "threadRef": "qa-missing"},
            "ANSWER_FOLLOW_UP",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    assert response.json()["error"]["category"] == "POLICY"


async def test_follow_up_forged_thread_ref_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["qaThreadDigest"] = {"threadRef": "qa-7", "summary": "편차 질문"}
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "FOLLOW_UP", "threadRef": "qa-forged"},
            "ANSWER_FOLLOW_UP",
        )
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 502
    assert response.json()["error"]["category"] == "POLICY"
    assert len(fake_llm.calls) == 1


@pytest.mark.parametrize("mode_alias", ["FOLLOWUP", "follow-up"])
def test_policy_normalizes_follow_up_aliases(
    turn_payload: dict[str, object],
    mode_alias: str,
) -> None:
    payload = deepcopy(turn_payload)
    context_payload = payload["context"]
    assert isinstance(context_payload, dict)
    context_payload["qaThreadDigest"] = {
        "threadRef": "qa-7",
        "summary": "편차 질문",
    }
    context = ContextBuilder().build(TurnRequest.model_validate(payload))
    plan = make_plan(
        ToolName.ANSWER_QUESTION,
        {"qaThreadMode": mode_alias, "threadRef": "qa-7"},
        "ANSWER_FOLLOW_UP",
    )

    corrected, adjustments = PolicyVerifier().verify(plan, context)

    assert corrected.actions[0].args == {
        "qaThreadMode": "FOLLOW_UP",
        "threadRef": "qa-7",
    }
    assert adjustments == []


async def test_unknown_qa_thread_mode_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "CONTINUE", "threadRef": None},
            "ANSWER_USER_QUESTION",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    assert response.json()["error"]["category"] == "POLICY"
    assert len(fake_llm.calls) == 1


async def test_intervention_budget_violation_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    first = PlanAction(
        action_id="action-1",
        tool=ToolName.ANSWER_QUESTION,
        args={"qaThreadMode": "START_NEW", "threadRef": None},
    )
    fake_llm.queue(
        TurnPlan(
            turn_goal="ANSWER_USER_QUESTION",
            pedagogy_policy=PedagogyPolicy(
                mode="GROUND_FIRST",
                reason="budget test",
                allow_direct_answer=True,
                hint_depth="MEDIUM",
                intervention_budget=1,
            ),
            actions=[first, first.model_copy(update={"action_id": "action-2"})],
            reason="budget violation",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    assert response.json()["error"]["category"] == "POLICY"
    assert len(fake_llm.calls) == 1


def test_policy_silently_removes_extra_action_args(
    turn_payload: dict[str, object],
) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    plan = make_plan(
        ToolName.ANSWER_QUESTION,
        {
            "qaThreadMode": "START_NEW",
            "threadRef": None,
            "uncontracted": "discard me",
        },
        "ANSWER_USER_QUESTION",
    )

    corrected, adjustments = PolicyVerifier().verify(plan, context)

    assert corrected.actions[0].args == {
        "qaThreadMode": "START_NEW",
        "threadRef": None,
    }
    assert adjustments == []


async def test_event_payload_mismatch_returns_schema_envelope(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "USER_QUESTION",
        "payload": {"detailLevel": "DETAILED"},
    }

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 422
    assert response.json()["error"]["category"] == "SCHEMA"


@pytest.mark.parametrize("invalid_confidence", [0.7, "VERY_HIGH"])
async def test_learner_confidence_rejects_float_and_unknown_enum(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    invalid_confidence: object,
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["learnerConfidence"] = invalid_confidence

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 422
    assert response.json()["error"]["category"] == "SCHEMA"
    assert fake_llm.calls == []

async def test_dispatcher_marks_partial_failure(
    fake_llm: FakeLlm,
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    first = PlanAction(
        action_id="action-1",
        tool=ToolName.ANSWER_QUESTION,
        args={"qaThreadMode": "START_NEW", "threadRef": None},
    )
    second = first.model_copy(update={"action_id": "action-2"})
    plan = TurnPlan(
        turn_goal="ANSWER_USER_QUESTION",
        pedagogy_policy=PedagogyPolicy(
            mode="GROUND_FIRST",
            reason="partial failure test",
            allow_direct_answer=True,
            hint_depth="MEDIUM",
            intervention_budget=2,
        ),
        actions=[first, second],
        reason="partial failure test",
    )
    PolicyVerifier().verify(plan, context)
    fake_llm.queue(
        AgentOutput(markdown="first answer", thought_summary="first"),
        LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True),
    )
    dispatcher = ToolDispatcher(
        explainer=ExplainerAgent(llm=fake_llm, profile=settings.explainer_llm_profile),
        qa=QaAgent(llm=fake_llm, profile=settings.qa_llm_profile),
        model=settings.model_name,
    )

    result = await dispatcher.dispatch(
        plan,
        context,
        TurnDeadline.start(180),
    )

    assert [action.status for action in result.actions] == ["SUCCESS", "FAILED"]
    assert result.messages[0].content == "first answer"
    assert isinstance(result.failure, LlmBridgeError)


def test_state_patch_allowlist_rejects_unknown_key() -> None:
    with pytest.raises(PolicyViolation):
        merge_state_patch({}, {"sessionStatus": "COMPLETED"})


def test_state_patch_rejects_conflicting_values() -> None:
    with pytest.raises(PolicyViolation):
        merge_state_patch(
            {"pageStatus": "EXPLAINING"},
            {"pageStatus": "EXPLAINED"},
        )


def test_state_patch_accepts_start_new_without_thread_ref() -> None:
    assert merge_state_patch(
        {},
        {"qaThread": {"mode": "START_NEW"}},
    ) == {"qaThread": {"mode": "START_NEW"}}


def test_state_patch_requires_follow_up_thread_ref() -> None:
    with pytest.raises(PolicyViolation):
        merge_state_patch(
            {},
            {"qaThread": {"mode": "FOLLOW_UP"}},
        )
