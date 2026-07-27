"""Planner-only context compaction."""

import json
from copy import deepcopy

import httpx

from edupilot_ai.models.plan import AgentOutput, ToolName
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.context import ContextBuilder, PlanContext
from tests.fakes import FakeLlm
from tests.test_turn_contract import make_plan, post_turn


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
            "previousPageText": "",
            "nextPageText": None,
            "recentMessages": [
                {"role": "user", "content": f"old-{index}-" + "M" * 140}
                for index in range(4)
            ],
            "qaThreadDigest": {
                "threadRef": "qa-77",
                "summary": "앞선 질문 요약",
                "privateTranscript": "포함하면 안 되는 전문",
            },
            "quizAssessments": [
                {"assessmentId": 1, "summary": "이전 평가"},
                {"assessmentId": 2, "summary": "최신 평가"},
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
        {"assessmentId": 2, "summary": "최신 평가"}
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
    context["recentMessages"] = [
        {"role": "user", "content": "질문 전문 " + "Y" * 200}
    ]
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        ),
        AgentOutput(markdown="답변", thought_summary="근거 연결"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    planner_payload = json.loads(fake_llm.calls[0][0][1]["content"])
    agent_payload = json.loads(fake_llm.calls[1][0][1]["content"])
    assert planner_payload["pageTextPreview"] == page_text[:500]
    assert page_text not in fake_llm.calls[0][0][1]["content"]
    assert len(planner_payload["recentMessages"][0]["content"]) == 120
    assert agent_payload["currentPageText"] == page_text
