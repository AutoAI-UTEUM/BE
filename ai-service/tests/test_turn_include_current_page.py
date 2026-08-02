"""v0.5 includeCurrentPage and conditional page-context contract tests."""

import json
from copy import deepcopy

import httpx

from edupilot_ai.core.errors import InternalErrorResponse
from edupilot_ai.models.plan import AgentOutput, ToolName, TurnPlan
from edupilot_ai.models.turn import TurnResponse
from tests.fakes import FakeLlm
from tests.test_turn_contract import make_plan, post_turn


def _detach_page(payload: dict[str, object]) -> None:
    payload["event"] = {
        "eventType": "USER_QUESTION",
        "payload": {
            "message": "표준편차가 왜 필요한가요?",
            "includeCurrentPage": False,
        },
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["currentPageText"] = None
    context["previousPageText"] = None
    context["nextPageText"] = None


def _qa_plan() -> TurnPlan:
    return make_plan(
        ToolName.ANSWER_QUESTION,
        {"qaThreadMode": "START_NEW", "threadRef": None},
        "ANSWER_USER_QUESTION",
    )


async def test_include_current_page_false_returns_qa_answer(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    _detach_page(payload)
    fake_llm.queue(
        _qa_plan(),
        AgentOutput(
            markdown="표준편차는 자료가 평균에서 얼마나 퍼져 있는지 보여줍니다.",
            thought_summary="일반 학습 지식으로 답변",
        ),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    turn = TurnResponse.model_validate(response.json())
    assert turn.messages[0].message_type == "QA"
    assert "제공된 강의 자료만으로는" not in turn.messages[0].content
    assert len(fake_llm.calls) == 2
    planner_payload = json.loads(fake_llm.calls[0][0][1]["content"])
    assert planner_payload["eventPayload"]["includeCurrentPage"] is False
    assert planner_payload["pageTextPreview"] == ""
    qa_system = fake_llm.calls[1][0][0]["content"]
    assert "일반적인 학습 지식으로 답해도 된다" in qa_system
    assert "업로드된 강의 자료에 어떤 내용이 있는지 추측" in qa_system
    qa_payload = json.loads(fake_llm.calls[1][0][1]["content"])
    assert qa_payload["includeCurrentPage"] is False
    assert qa_payload["currentPageText"] is None


async def test_explain_event_rejects_null_current_page_text(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "NORMAL"},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["currentPageText"] = None

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 422
    body = response.json()
    assert set(body) == {"schemaVersion", "error", "traceId"}
    assert set(body["error"]) == {"code", "category", "message", "retryable"}
    error = InternalErrorResponse.model_validate(body)
    assert error.error.code == "AI_REQUEST_INVALID"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False


async def test_include_current_page_omitted_defaults_to_true(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    fake_llm.queue(
        _qa_plan(),
        AgentOutput(markdown="편차는 관측값과 평균의 차이입니다.", thought_summary="답변"),
    )

    response = await post_turn(client, auth_headers, payload)

    assert response.status_code == 200
    qa_payload = json.loads(fake_llm.calls[1][0][1]["content"])
    assert qa_payload["includeCurrentPage"] is True
    assert qa_payload["currentPageText"] == "편차 설명"
    qa_system = fake_llm.calls[1][0][0]["content"]
    assert "페이지를 첨부하지 않은 질문이다" not in qa_system


async def test_include_current_page_false_streams_qa_answer(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    _detach_page(payload)
    fake_llm.queue(_qa_plan())
    fake_llm.queue_text_stream("표준편차는 ", "자료의 퍼짐을 나타냅니다.")

    response = await client.post(
        "/internal/ai/turn",
        headers={**auth_headers, "Accept": "application/x-ndjson"},
        json=payload,
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines() if line]
    assert events[-1]["type"] == "completed"
    completed = TurnResponse.model_validate(events[-1]["result"])
    assert completed.messages[0].content == "표준편차는 자료의 퍼짐을 나타냅니다."
    assert "제공된 강의 자료만으로는" not in completed.messages[0].content
    assert len(fake_llm.stream_calls) == 1
