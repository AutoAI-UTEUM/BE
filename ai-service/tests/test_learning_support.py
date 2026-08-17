"""Draft #36 learning-support contracts and policy gates."""

import json
from copy import deepcopy

import httpx
import pytest

from edupilot_ai.core.errors import ErrorCategory, InternalErrorResponse
from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.learning_support import (
    AssessmentOutput,
    DiagnosisOutput,
    MemoryCandidate,
    RepairOutput,
)
from edupilot_ai.models.plan import AgentOutput, PlanAction, ToolName, TurnPlan
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.policy import PolicyVerifier, PolicyViolation
from edupilot_ai.settings import ReasoningEffort
from tests.fakes import FakeLlm
from tests.test_turn_contract import make_plan, post_turn


def plan_with_memory_action(
    tool: ToolName,
    args: dict[str, object],
    goal: str,
) -> TurnPlan:
    plan = make_plan(
        ToolName.ANSWER_QUESTION,
        {"qaThreadMode": "START_NEW", "threadRef": None},
        goal,
    )
    return plan.model_copy(
        update={
            "pedagogy_policy": plan.pedagogy_policy.model_copy(
                update={"intervention_budget": 2}
            ),
            "actions": [
                *plan.actions,
                PlanAction(action_id="action-2", tool=tool, args=args),
            ],
        }
    )


def temporary_candidate(
    candidate_id: int,
    *,
    confidence: float = 0.8,
    evidence_source_id: int | None = None,
) -> dict[str, object]:
    source_id = evidence_source_id if evidence_source_id is not None else candidate_id
    return {
        "candidateId": candidate_id,
        "type": "MISCONCEPTION",
        "content": f"승격 후보 {candidate_id}",
        "confidence": confidence,
        "evidenceRefs": [
            {
                "sourceType": "QUIZ_ASSESSMENT",
                "sourceId": source_id,
                "sessionId": 100,
                "reference": None,
            }
        ],
    }


def set_temporary_candidates(
    payload: dict[str, object],
    candidates: list[dict[str, object]],
) -> None:
    context = payload["context"]
    assert isinstance(context, dict)
    memory = context["memory"]
    assert isinstance(memory, dict)
    memory["temporaryCandidates"] = candidates


def quiz_result() -> dict[str, object]:
    return {
        "quizId": 50,
        "quizType": "ESSAY",
        "score": 40,
        "maxScore": 100,
        "passed": False,
        "items": [
            {
                "questionId": "q-1",
                "score": 4,
                "maxScore": 10,
                "verdict": "PARTIAL",
                "feedback": "평균과 편차의 관계를 보완하세요.",
            }
        ],
    }


def page_context() -> dict[str, object]:
    return {
        "coverageStartPage": 3,
        "coverageEndPage": 3,
        "text": "편차는 관측값과 평균의 차이입니다.",
    }


def assessment_payload() -> dict[str, object]:
    return {
        "schemaVersion": "1.0",
        "quizResult": quiz_result(),
        "quizItems": [
            {
                "questionId": "q-1",
                "question": "편차를 설명하세요.",
                "modelAnswer": "관측값과 평균의 차이",
            }
        ],
        "studentAnswers": [{"questionId": "q-1", "answer": "평균 자체"}],
        "pageContext": page_context(),
        "learnerMemoryDigest": None,
    }


def diagnosis_payload() -> dict[str, object]:
    return {
        "schemaVersion": "1.0",
        "quizAssessment": {
            "understandingSummary": "편차와 평균을 혼동할 가능성이 있습니다.",
            "weaknesses": ["편차 정의"],
        },
        "quizResult": quiz_result(),
        "wrongItems": [
            {
                "questionId": "q-1",
                "question": "편차를 설명하세요.",
                "studentAnswer": "평균 자체",
                "modelAnswer": "관측값과 평균의 차이",
                "feedback": "두 개념의 관계를 다시 확인하세요.",
            }
        ],
        "pageContext": page_context(),
        "learnerMemoryDigest": None,
    }


def assessment_output() -> AssessmentOutput:
    return AssessmentOutput(
        understanding_summary="편차 정의를 부분적으로 이해했습니다.",
        strengths=["평균 개념을 언급함"],
        weaknesses=["편차와 평균을 구분하지 못함"],
        suspected_misconceptions=["편차를 평균값 자체로 보는 경향 가능성"],
        recommended_next_direction="편차 정의를 짧게 복습",
        memory_candidates=[
            MemoryCandidate(
                type="WEAKNESS",
                content="편차와 평균 구분",
                confidence=0.6,
            )
        ],
        evidence=["q-1 답안에서 평균 자체라고 표현"],
    )


def diagnosis_output() -> DiagnosisOutput:
    return DiagnosisOutput(
        focus_concepts=["편차의 정의"],
        suspected_misconceptions=["평균과 편차를 같은 값으로 이해할 가능성"],
        diagnostic_prompt="편차는 평균 자체인가요, 평균과의 차이인가요?",
        evidence=["q-1에서 평균 자체라고 답함"],
        repair_hint="관측값과 평균의 차이를 예로 연결",
    )


async def test_quiz_assessment_endpoint_uses_high_reasoning(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(assessment_output())

    response = await client.post(
        "/internal/ai/quiz-assessment",
        headers=auth_headers,
        json=assessment_payload(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["understandingSummary"].startswith("편차")
    assert body["memoryCandidates"][0]["confidence"] == 0.6
    assert body["usage"]["model"] == "grok-4.5"
    assert fake_llm.calls[0][1].reasoning_effort is ReasoningEffort.HIGH
    assert fake_llm.timeouts == [45]
    system = fake_llm.calls[0][0][0]["content"]
    assert "단일 퀴즈나 단일 답변만으로" in system
    assert "성격, 능력" in system


async def test_diagnosis_endpoint_does_not_instruct_answer_disclosure(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(diagnosis_output())

    response = await client.post(
        "/internal/ai/diagnosis",
        headers=auth_headers,
        json=diagnosis_payload(),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["diagnosticPrompt"].endswith("?")
    assert "관측값과 평균의 차이" not in body["diagnosticPrompt"]
    assert fake_llm.calls[0][1].reasoning_effort is ReasoningEffort.HIGH
    assert fake_llm.timeouts == [45]
    assert "정답, modelAnswer 또는 전체 해설을 먼저 제공하지 마라" in (
        fake_llm.calls[0][0][0]["content"]
    )


async def test_quiz_assessment_schema_failure_regenerates_once(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(
            category=ErrorCategory.SCHEMA,
            retryable=False,
            usage=LlmUsage("grok-4.5", 3, 2, 1),
        )
    )
    fake_llm.queue_completion(
        assessment_output(),
        LlmUsage("grok-4.5", 7, 4, 2),
    )

    response = await client.post(
        "/internal/ai/quiz-assessment",
        headers=auth_headers,
        json=assessment_payload(),
    )

    assert response.status_code == 200
    assert len(fake_llm.calls) == 2
    assert "정확히 한 번 재생성하세요" in fake_llm.calls[1][0][0]["content"]
    assert response.json()["usage"] == {
        "model": "grok-4.5",
        "inputTokens": 10,
        "outputTokens": 6,
        "reasoningTokens": 3,
    }


async def test_quiz_assessment_schema_failure_twice_returns_502(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
    )

    response = await client.post(
        "/internal/ai/quiz-assessment",
        headers=auth_headers,
        json=assessment_payload(),
    )

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_RESPONSE_INVALID"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False
    assert len(fake_llm.calls) == 2
    assert "정확히 한 번 재생성하세요" in fake_llm.calls[1][0][0]["content"]


async def test_diagnosis_schema_failure_regenerates_once(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(
            category=ErrorCategory.SCHEMA,
            retryable=False,
            usage=LlmUsage("grok-4.5", 5, 3, 2),
        )
    )
    fake_llm.queue_completion(
        diagnosis_output(),
        LlmUsage("grok-4.5", 8, 5, 3),
    )

    response = await client.post(
        "/internal/ai/diagnosis",
        headers=auth_headers,
        json=diagnosis_payload(),
    )

    assert response.status_code == 200
    assert len(fake_llm.calls) == 2
    assert "정확히 한 번 재생성하세요" in fake_llm.calls[1][0][0]["content"]
    assert response.json()["usage"] == {
        "model": "grok-4.5",
        "inputTokens": 13,
        "outputTokens": 8,
        "reasoningTokens": 5,
    }


async def test_diagnosis_schema_failure_twice_returns_502(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
    )

    response = await client.post(
        "/internal/ai/diagnosis",
        headers=auth_headers,
        json=diagnosis_payload(),
    )

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_RESPONSE_INVALID"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False
    assert len(fake_llm.calls) == 2
    assert "정확히 한 번 재생성하세요" in fake_llm.calls[1][0][0]["content"]


def repair_turn_payload(turn_payload: dict[str, object]) -> dict[str, object]:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "DIAGNOSIS_ANSWER_SUBMITTED",
        "payload": {"diagnosisId": 30, "answer": "편차가 평균값이라고 생각했습니다."},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["pendingDiagnosis"] = {
        "diagnosisId": 30,
        "focusConcepts": ["편차의 정의"],
        "repairHint": "평균과의 차이를 연결",
    }
    return payload


async def test_repair_turn_replaces_stub_and_clears_pending_diagnosis(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.REPAIR_MISCONCEPTION,
            {"diagnosisId": 30},
            "REPAIR_MISCONCEPTION",
        ),
        RepairOutput(
            markdown="## 오개념 교정\n\n편차는 평균이 아니라 평균과의 **차이**입니다.",
        ),
    )

    response = await post_turn(
        client,
        auth_headers,
        repair_turn_payload(turn_payload),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["messages"][0]["messageType"] == "REPAIR"
    assert body["messages"][0]["content"].startswith("## 오개념 교정")
    assert body["statePatch"] == {
        "pageStatus": "REPAIR_COMPLETED",
        "pendingDiagnosis": None,
    }
    assert fake_llm.calls[1][1].reasoning_effort is ReasoningEffort.MEDIUM
    assert "현재 페이지 전체를 다시 설명하거나" in fake_llm.calls[1][0][0]["content"]
    assert "모든 학습자 대상 텍스트" in fake_llm.calls[1][0][0]["content"]


async def test_repair_without_pending_diagnosis_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = repair_turn_payload(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["pendingDiagnosis"] = None
    fake_llm.queue(
        make_plan(
            ToolName.REPAIR_MISCONCEPTION,
            {"diagnosisId": 30},
            "REPAIR_MISCONCEPTION",
        )
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "AI_POLICY_REJECTED"
    assert len(fake_llm.calls) == 1


async def test_memory_promotion_rejects_empty_candidate_ids(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.PROMOTE_MEMORY,
            {"candidateIds": []},
            "PROMOTE_MEMORY",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"


async def test_memory_promotion_rejects_unknown_candidate_id(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    set_temporary_candidates(turn_payload, [temporary_candidate(101)])
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.PROMOTE_MEMORY,
            {"candidateIds": [101, 999]},
            "PROMOTE_MEMORY",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"


async def test_memory_promotion_rejects_low_confidence_candidate(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    set_temporary_candidates(
        turn_payload,
        [
            temporary_candidate(101),
            temporary_candidate(102, confidence=0.6),
        ],
    )
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.PROMOTE_MEMORY,
            {"candidateIds": [101, 102]},
            "PROMOTE_MEMORY",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"


async def test_memory_promotion_rejects_single_unique_evidence(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    set_temporary_candidates(
        turn_payload,
        [
            temporary_candidate(101, evidence_source_id=501),
            temporary_candidate(102, evidence_source_id=501),
        ],
    )
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.PROMOTE_MEMORY,
            {"candidateIds": [101, 102]},
            "PROMOTE_MEMORY",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"


async def test_memory_tool_only_plan_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.BUILD_MEMORY_CANDIDATE,
            {
                "type": "WEAKNESS",
                "content": "편차 정의를 반복해서 혼동함",
                "confidence": 0.65,
                "evidence": ["assessment-1"],
            },
            "BUILD_MEMORY_CANDIDATE",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"
    assert len(fake_llm.calls) == 1


async def test_memory_candidate_is_returned_after_primary_action(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.BUILD_MEMORY_CANDIDATE,
            {
                "type": "WEAKNESS",
                "content": "편차 정의를 반복해서 혼동함",
                "confidence": 0.65,
                "evidence": ["assessment-1"],
            },
            "BUILD_MEMORY_CANDIDATE",
        ),
        AgentOutput(
            markdown="편차는 관측값과 평균의 차이입니다.",
        ),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["memoryCandidates"] == [
        {
            "type": "WEAKNESS",
            "content": "편차 정의를 반복해서 혼동함",
            "confidence": 0.65,
            "evidence": ["assessment-1"],
            "promotionRequested": False,
        }
    ]
    assert body["memoryWrite"] is None
    assert len(fake_llm.calls) == 2


async def test_memory_promotion_returns_candidate_ids_contract(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    set_temporary_candidates(
        turn_payload,
        [
            temporary_candidate(101, evidence_source_id=501),
            temporary_candidate(102, evidence_source_id=502),
        ],
    )
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.PROMOTE_MEMORY,
            {"candidateIds": [101, 102]},
            "PROMOTE_MEMORY",
        ),
        AgentOutput(
            markdown="편차는 관측값과 평균의 차이입니다.",
        ),
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 200
    body = response.json()
    turn = TurnResponse.model_validate(body)
    assert body["memoryCandidates"] == []
    expected_memory_write = {"candidateIds": [101, 102]}
    assert body["memoryWrite"] == expected_memory_write
    assert turn.memory_write == expected_memory_write
    planner_payload = json.loads(fake_llm.calls[0][0][1]["content"])
    assert [
        candidate["candidateId"]
        for candidate in planner_payload["memory"]["temporaryCandidates"]
    ] == [101, 102]


def test_multiple_memory_promotions_are_rejected(
    turn_payload: dict[str, object],
) -> None:
    promotion_args: dict[str, object] = {"candidateIds": [101, 102]}
    plan = plan_with_memory_action(
        ToolName.PROMOTE_MEMORY,
        promotion_args,
        "PROMOTE_MEMORY",
    )
    plan = plan.model_copy(
        update={
            "pedagogy_policy": plan.pedagogy_policy.model_copy(
                update={"intervention_budget": 3}
            ),
            "actions": [
                *plan.actions,
                PlanAction(
                    action_id="action-3",
                    tool=ToolName.PROMOTE_MEMORY,
                    args=promotion_args,
                ),
            ],
        }
    )
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))

    with pytest.raises(
        PolicyViolation,
        match="^multiple memory promotions in one turn$",
    ):
        PolicyVerifier().verify(plan, context)


async def test_personality_memory_type_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.BUILD_MEMORY_CANDIDATE,
            {
                "type": "PERSONALITY",
                "content": "학생은 소극적이다",
                "confidence": 0.9,
                "evidence": ["qa-1"],
            },
            "BUILD_MEMORY_CANDIDATE",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"
