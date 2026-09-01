"""Planner-only context compaction."""

import json
from copy import deepcopy

import httpx
import pytest

from edupilot_ai.models.learning_support import RepairOutput
from edupilot_ai.models.plan import AgentOutput, ToolName
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.context import ContextBuilder, PlanContext
from tests.fakes import FakeLlm
from tests.test_turn_contract import make_plan, post_turn


@pytest.mark.parametrize(
    ("role_key", "summary_key"),
    [
        ("senderType", "digest"),
        ("role", "summary"),
    ],
)
def test_plan_context_reads_current_and_legacy_snapshot_keys(
    turn_payload: dict[str, object],
    role_key: str,
    summary_key: str,
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["recentMessages"] = [{role_key: "STUDENT", "content": "편차가 궁금해요."}]
    context["qaThreadDigest"] = {
        "threadRef": "qa-77",
        summary_key: "편차에 관한 질문 요약",
    }

    plan_context = PlanContext.from_agent_context(
        ContextBuilder().build(TurnRequest.model_validate(payload))
    )

    assert plan_context.recent_messages[0].role == "STUDENT"
    assert plan_context.qa_thread_digest is not None
    assert plan_context.qa_thread_digest.has_summary is True


def test_plan_context_contains_only_bounded_planner_fields(
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    session = payload["session"]
    context = payload["context"]
    assert isinstance(session, dict)
    assert isinstance(context, dict)
    context.update(
        {
            "currentPageText": "P" * 600,
            "xaiFileId": "file-must-never-reach-planner",
            "previousPageText": "",
            "nextPageText": None,
            "recentMessages": [
                {"role": "user", "content": f"old-{index}-" + "M" * 140} for index in range(4)
            ],
            "qaThreadDigest": {
                "threadRef": "qa-77",
                "summary": "앞선 질문 요약",
                "privateTranscript": "포함하면 안 되는 전문",
            },
            "quizAssessments": [
                {
                    "understandingSummary": "이전 평가",
                    "recommendedNextDirection": "복습",
                    "weaknesses": ["평균"],
                    "privateEvidence": "이전 비공개 근거",
                },
                {
                    "understandingSummary": "최신 평가",
                    "recommendedNextDirection": "응용 문제",
                    "weaknesses": ["편차"],
                    "privateEvidence": "최신 비공개 근거",
                },
            ],
            "learnerLevel": "INTERMEDIATE",
            "learnerConfidence": "HIGH",
            "pendingDiagnosis": {
                "diagnosisId": 44,
                "answer": "플래너에 전달하면 안 되는 학생 답안",
            },
            "latestRepair": {"content": "플래너에 전달하면 안 되는 교정 원문"},
        }
    )
    turn = TurnRequest.model_validate(payload)

    plan_context = PlanContext.from_agent_context(ContextBuilder().build(turn))
    serialized = plan_context.model_dump(mode="json", by_alias=True)

    assert serialized["turnId"] == "turn-123"
    assert serialized["session"] == {
        "currentPage": session["currentPage"],
        "pageStatus": session["pageStatus"],
    }
    assert "sessionId" not in json.dumps(serialized["session"])
    assert "userId" not in json.dumps(serialized["session"])
    assert "materialId" not in json.dumps(serialized["session"])
    assert serialized["eventType"] == "USER_QUESTION"
    assert serialized["eventPayload"] == {"message": "편차가 뭔지 모르겠어"}
    assert serialized["hasMaterialAttachment"] is True
    assert serialized["pageTextPreview"] == "P" * 500
    assert serialized["hasPreviousPageText"] is True
    assert serialized["hasNextPageText"] is False
    assert len(serialized["recentMessages"]) == 3
    assert [item["role"] for item in serialized["recentMessages"]] == [
        "user",
        "user",
        "user",
    ]
    assert all(len(item["content"]) == 120 for item in serialized["recentMessages"])
    assert serialized["qaThreadDigest"] == {
        "threadRef": "qa-77",
        "hasSummary": True,
    }
    assert serialized["quizAssessments"] == [
        {
            "understandingSummary": "최신 평가",
            "recommendedNextDirection": "응용 문제",
            "weaknesses": ["편차"],
        }
    ]
    assert serialized["learnerLevel"] == "INTERMEDIATE"
    assert serialized["learnerConfidence"] == "HIGH"
    assert serialized["hasPendingDiagnosis"] is True
    assert serialized["pendingDiagnosisId"] == 44
    assert serialized["hasLatestRepair"] is True
    serialized_text = json.dumps(serialized, ensure_ascii=False)
    assert "포함하면 안 되는 전문" not in serialized_text
    assert "플래너에 전달하면 안 되는 학생 답안" not in serialized_text
    assert "플래너에 전달하면 안 되는 교정 원문" not in serialized_text
    assert "최신 비공개 근거" not in serialized_text
    assert "xaiFileId" not in serialized_text
    assert "file-must-never-reach-planner" not in serialized_text


def test_diagnosis_answer_is_not_serialized_into_plan_context(
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "DIAGNOSIS_ANSWER_SUBMITTED",
        "payload": {
            "diagnosisId": 44,
            "answer": "학생이 작성한 민감한 진단 답변 원문",
        },
    }
    turn = TurnRequest.model_validate(payload)

    plan_context = PlanContext.from_agent_context(ContextBuilder().build(turn))
    serialized = plan_context.model_dump(mode="json", by_alias=True)

    assert serialized["eventPayload"] == {"diagnosisId": 44}
    assert "학생이 작성한 민감한 진단 답변 원문" not in json.dumps(
        serialized,
        ensure_ascii=False,
    )


def test_quiz_type_selected_keeps_bounded_plan_context(
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "QUIZ_TYPE_SELECTED",
        "payload": {"quizType": "ESSAY"},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    page_text = "퀴즈 근거 " + "Q" * 700
    context["currentPageText"] = page_text
    turn = TurnRequest.model_validate(payload)

    plan_context = PlanContext.from_agent_context(ContextBuilder().build(turn))
    serialized = plan_context.model_dump(mode="json", by_alias=True)

    assert serialized["eventPayload"] == {"quizType": "ESSAY"}
    assert len(serialized["pageTextPreview"]) == 500
    assert page_text not in json.dumps(
        serialized,
        ensure_ascii=False,
    )


async def test_only_planner_receives_slim_context(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    page_text = "현재 페이지 전체 문맥 " + "X" * 700
    context["currentPageText"] = page_text
    context["recentMessages"] = [{"senderType": "STUDENT", "content": "질문 전문 " + "Y" * 200}]
    context["qaThreadDigest"] = {
        "threadRef": "qa-88",
        "digest": "Spring 형식 질문 요약",
    }
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        ),
        AgentOutput(markdown="답변"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    planner_payload = json.loads(fake_llm.calls[0][0][1]["content"])
    agent_payload = json.loads(fake_llm.calls[1][0][1]["content"])
    assert planner_payload["pageTextPreview"] == page_text[:500]
    assert page_text not in fake_llm.calls[0][0][1]["content"]
    assert len(planner_payload["recentMessages"][0]["content"]) == 120
    assert planner_payload["recentMessages"][0]["role"] == "STUDENT"
    assert planner_payload["qaThreadDigest"] == {
        "threadRef": "qa-88",
        "hasSummary": True,
    }
    assert agent_payload["currentPageText"] == page_text


async def test_repair_agent_keeps_full_context_outside_planner(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    answer = "편차를 평균값 자체라고 생각했습니다."
    page_text = "편차는 관측값과 평균의 차이입니다. " + "R" * 700
    payload["event"] = {
        "eventType": "DIAGNOSIS_ANSWER_SUBMITTED",
        "payload": {"diagnosisId": 44, "answer": answer},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["currentPageText"] = page_text
    context["pendingDiagnosis"] = {
        "diagnosisId": 44,
        "focusConcepts": ["편차의 정의"],
        "repairHint": "평균과의 차이를 연결",
    }
    fake_llm.queue(
        make_plan(
            ToolName.REPAIR_MISCONCEPTION,
            {"diagnosisId": 44},
            "REPAIR_MISCONCEPTION",
        ),
        RepairOutput(
            markdown="## 오개념 교정\n\n편차는 평균과의 차이입니다.",
        ),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    planner_payload = json.loads(fake_llm.calls[0][0][1]["content"])
    repair_payload = json.loads(fake_llm.calls[1][0][1]["content"])
    assert planner_payload["eventPayload"] == {"diagnosisId": 44}
    assert answer not in fake_llm.calls[0][0][1]["content"]
    assert planner_payload["pageTextPreview"] == page_text[:500]
    assert repair_payload["studentAnswer"] == answer
    assert repair_payload["pageText"] == page_text
    assert repair_payload["diagnosis"] == context["pendingDiagnosis"]
