"""Draft #36 learning-support contracts and policy gates."""

from copy import deepcopy

import httpx

from edupilot_ai.core.errors import InternalErrorResponse
from edupilot_ai.models.learning_support import (
    AssessmentOutput,
    DiagnosisOutput,
    MemoryCandidate,
    RepairOutput,
)
from edupilot_ai.models.plan import ToolName
from edupilot_ai.settings import ReasoningEffort
from tests.fakes import FakeLlm
from tests.test_turn_contract import make_plan, post_turn


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


async def test_quiz_assessment_endpoint_uses_high_reasoning(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        AssessmentOutput(
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
    )

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
    fake_llm.queue(
        DiagnosisOutput(
            focus_concepts=["편차의 정의"],
            suspected_misconceptions=["평균과 편차를 같은 값으로 이해할 가능성"],
            diagnostic_prompt="편차는 평균 자체인가요, 평균과의 차이인가요?",
            evidence=["q-1에서 평균 자체라고 답함"],
            repair_hint="관측값과 평균의 차이를 예로 연결",
        )
    )

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
            focus_concepts=["편차의 정의"],
            thought_summary="평균과 편차의 관계를 교정",
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


async def test_memory_promotion_requires_two_evidence_and_confidence(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
            ToolName.PROMOTE_MEMORY,
            {
                "type": "WEAKNESS",
                "content": "편차 정의",
                "confidence": 0.6,
                "evidence": ["assessment-1"],
            },
            "PROMOTE_MEMORY",
        )
    )

    response = await post_turn(client, auth_headers, turn_payload)

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "POLICY"


async def test_memory_candidate_is_returned_without_agent_llm_call(
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
    assert len(fake_llm.calls) == 1


async def test_personality_memory_type_is_rejected(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        make_plan(
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
