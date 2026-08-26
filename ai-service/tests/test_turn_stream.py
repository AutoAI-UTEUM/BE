"""NDJSON turn stream contract and timeout budget tests."""

import asyncio
import json
import time
from collections.abc import AsyncGenerator, Mapping, Sequence
from copy import deepcopy

import httpx
from fastapi import FastAPI

from edupilot_ai.api.deps import get_turn_service
from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmCompletion,
    LlmFileAttachment,
    LlmUsage,
    ModelT,
)
from edupilot_ai.models.plan import (
    AgentOutput,
    PedagogyPolicy,
    PlanAction,
    ToolName,
    TurnPlan,
)
from edupilot_ai.models.quiz import QuizCoverage, QuizType
from edupilot_ai.models.stream import (
    CompletedStreamEvent,
    ErrorStreamEvent,
    StatusStreamEvent,
    TurnStreamEvent,
)
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.agents import ExplainerAgent, QaAgent, QuizAgent
from edupilot_ai.orchestration.context import ContextBuilder
from edupilot_ai.orchestration.dispatcher import ToolDispatcher
from edupilot_ai.orchestration.orchestrator import Orchestrator
from edupilot_ai.orchestration.policy import PolicyVerifier
from edupilot_ai.orchestration.service import TurnService, events_with_heartbeat
from edupilot_ai.orchestration.timing import MonotonicClock
from edupilot_ai.settings import AgentLlmProfile, Settings
from tests.fakes import FakeLlm
from tests.test_learning_support import (
    plan_with_memory_action,
    set_temporary_candidates,
    temporary_candidate,
)
from tests.test_quiz_grading import make_quiz


class SlowFakeLlm(FakeLlm):
    def __init__(self, *, delay_seconds: float) -> None:
        super().__init__()
        self._delay_seconds = delay_seconds

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment] = (),
    ) -> LlmCompletion[ModelT]:
        await asyncio.sleep(self._delay_seconds)
        return await super().complete_json(
            messages=messages,
            response_model=response_model,
            profile=profile,
            timeout_seconds=timeout_seconds,
            attachments=attachments,
        )


def make_plan(tool: ToolName, args: dict[str, object], goal: str) -> TurnPlan:
    return TurnPlan(
        turn_goal=goal,
        pedagogy_policy=PedagogyPolicy(
            mode="GROUND_FIRST",
            reason="stream contract test",
            allow_direct_answer=True,
            hint_depth="MEDIUM",
            intervention_budget=1,
        ),
        actions=[PlanAction(action_id="action-1", tool=tool, args=args)],
        reason="stream contract test plan",
    )


def parse_events(response: httpx.Response) -> list[dict[str, object]]:
    return [json.loads(line) for line in response.text.splitlines() if line]


def make_service(
    fake_llm: FakeLlm,
    settings: Settings,
    *,
    clock: MonotonicClock = time.monotonic,
    heartbeat_interval_seconds: float = 10,
) -> TurnService:
    return TurnService(
        context_builder=ContextBuilder(),
        orchestrator=Orchestrator(
            llm=fake_llm,
            profile=settings.orchestrator_llm_profile,
        ),
        policy=PolicyVerifier(),
        dispatcher=ToolDispatcher(
            explainer=ExplainerAgent(
                llm=fake_llm,
                profile=settings.explainer_llm_profile,
            ),
            qa=QaAgent(
                llm=fake_llm,
                profile=settings.qa_llm_profile,
            ),
            quiz=QuizAgent(
                llm=fake_llm,
                profile=settings.quiz_llm_profile,
            ),
            model=settings.model_name,
        ),
        model=settings.model_name,
        turn_timeout_seconds=settings.turn_timeout_seconds,
        first_event_timeout_seconds=settings.turn_first_event_timeout_seconds,
        heartbeat_interval_seconds=heartbeat_interval_seconds,
        clock=clock,
    )


async def test_explain_ndjson_golden_sequence_and_content_invariant(
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
    context["xaiFileId"] = "file-explain-stream"
    fake_llm.queue_text_stream(
        "편차는 ",
        "**평균과 관측값의 차이**입니다.",
        usage=LlmUsage("grok-4.5", 12, 8, 2),
    )

    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/x-ndjson")
    assert response.headers["X-Trace-Id"] == "contract-test-trace"
    events = parse_events(response)
    assert [event["type"] for event in events] == [
        "status",
        "thought_summary",
        "status",
        "thought_summary",
        "content_delta",
        "content_delta",
        "status",
        "completed",
    ]
    assert [event.get("stage") for event in events if event["type"] == "status"] == [
        "PLANNING",
        "EXPLAINING",
        "FINALIZING",
    ]
    assert events[1]["text"] == "학습 계획을 세우는 중입니다"
    assert events[3]["text"] == "3페이지 설명을 작성하는 중입니다"
    deltas = "".join(str(event["text"]) for event in events if event["type"] == "content_delta")
    completed = TurnResponse.model_validate(events[-1]["result"])
    assert deltas == "".join(message.content for message in completed.messages)
    assert completed.messages[0].message_type == "EXPLANATION"
    assert completed.usage.input_tokens == 12
    assert completed.usage.output_tokens == 8
    assert fake_llm.calls == []
    assert len(fake_llm.stream_calls) == 1
    assert 0 < fake_llm.stream_calls[0][2] <= 180
    stream_system_prompt = fake_llm.stream_calls[0][0][0]["content"]
    assert "Return only the learner-facing Markdown explanation." in stream_system_prompt
    assert "모든 학습자 대상 텍스트" in stream_system_prompt
    assert [item.file_id for item in fake_llm.stream_file_attachments[0]] == ["file-explain-stream"]


async def test_explain_empty_page_streams_fixed_guidance_without_agent_llm(
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
    context = payload["context"]
    assert isinstance(context, dict)
    context["currentPageText"] = ""
    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )

    assert response.status_code == 200
    events = parse_events(response)
    deltas = "".join(str(event["text"]) for event in events if event["type"] == "content_delta")
    completed = TurnResponse.model_validate(events[-1]["result"])
    assert deltas == (
        "이 페이지에는 설명할 텍스트 내용이 없어요. 이미지나 도형 중심 페이지라면 "
        "다음 페이지로 이동해 학습을 이어가 주세요."
    )
    assert deltas == completed.messages[0].content
    assert completed.state_patch == {"pageStatus": "EXPLAINED"}
    assert fake_llm.calls == []
    assert fake_llm.stream_calls == []


async def test_qa_ndjson_golden_sequence_preserves_thread_ref(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    context = payload["context"]
    assert isinstance(context, dict)
    context["qaThreadDigest"] = {"threadRef": "qa-11", "summary": "편차 질문"}
    context["xaiFileId"] = "file-qa-stream"
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "FOLLOW_UP", "threadRef": "qa-11"},
            "ANSWER_FOLLOW_UP",
        )
    )
    fake_llm.queue_text_stream(
        "앞선 설명과 연결하면 ",
        "편차의 부호는 방향을 뜻합니다.",
    )

    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )

    events = parse_events(response)
    assert events[-1]["type"] == "completed"
    assert any(event.get("stage") == "ANSWERING" for event in events if event["type"] == "status")
    result = TurnResponse.model_validate(events[-1]["result"])
    assert result.state_patch == {"qaThread": {"mode": "FOLLOW_UP", "threadRef": "qa-11"}}
    assert (
        "".join(str(event["text"]) for event in events if event["type"] == "content_delta")
        == result.messages[0].content
    )
    assert "모든 학습자 대상 텍스트" in fake_llm.stream_calls[0][0][0]["content"]
    assert fake_llm.file_attachments == [()]
    assert [item.file_id for item in fake_llm.stream_file_attachments[0]] == ["file-qa-stream"]


async def test_stream_error_is_terminal_and_excludes_completed(
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
        )
    )
    fake_llm.queue_text_stream(
        "확정되지 않은 일부 문장",
        LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True),
    )

    response = await client.post(
        "/internal/ai/turn",
        json=turn_payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )

    events = parse_events(response)
    assert events[-1] == {
        "type": "error",
        "code": "AI_SERVICE_TIMEOUT",
        "category": "TIMEOUT",
        "message": "The AI service could not complete the turn.",
        "retryable": True,
    }
    assert sum(event["type"] == "error" for event in events) == 1
    assert all(event["type"] != "completed" for event in events)


async def test_expired_turn_budget_stops_before_agent_call(
    fake_llm: FakeLlm,
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    readings = iter([0.0, 1.0, 181.0])
    service = make_service(fake_llm, settings, clock=lambda: next(readings))
    fake_llm.queue(
        make_plan(
            ToolName.ANSWER_QUESTION,
            {"qaThreadMode": "START_NEW", "threadRef": None},
            "ANSWER_USER_QUESTION",
        )
    )

    events = [
        event async for event in service.stream_events(TurnRequest.model_validate(turn_payload))
    ]

    terminal = events[-1]
    assert isinstance(terminal, ErrorStreamEvent)
    assert terminal.code == "AI_SERVICE_TIMEOUT"
    assert terminal.category is ErrorCategory.TIMEOUT
    assert fake_llm.timeouts == [179.0]
    assert fake_llm.stream_calls == []


async def test_heartbeat_emitted_after_silent_interval() -> None:
    async def slow_events() -> AsyncGenerator[TurnStreamEvent]:
        yield StatusStreamEvent(stage="PLANNING")
        await asyncio.sleep(0.025)
        yield StatusStreamEvent(stage="FINALIZING")

    events = [
        event
        async for event in events_with_heartbeat(
            slow_events(),
            first_event_timeout_seconds=0.01,
            heartbeat_interval_seconds=0.005,
        )
    ]

    assert events[0].type == "status"
    assert any(event.type == "heartbeat" for event in events[1:-1])
    assert events[-1].type == "status"


async def test_first_event_timeout_returns_one_terminal_error() -> None:
    async def delayed_first_event() -> AsyncGenerator[TurnStreamEvent]:
        await asyncio.sleep(0.025)
        yield StatusStreamEvent(stage="PLANNING")

    events = [
        event
        async for event in events_with_heartbeat(
            delayed_first_event(),
            first_event_timeout_seconds=0.005,
            heartbeat_interval_seconds=0.005,
        )
    ]

    assert len(events) == 1
    terminal = events[0]
    assert isinstance(terminal, ErrorStreamEvent)
    assert terminal.category is ErrorCategory.TIMEOUT


async def test_accept_omitted_keeps_json_path(
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
        AgentOutput(markdown="기존 JSON 답변"),
    )

    response = await client.post(
        "/internal/ai/turn",
        json=turn_payload,
        headers=auth_headers,
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    assert response.json()["messages"][0]["content"] == "기존 JSON 답변"
    assert fake_llm.stream_calls == []


async def test_quiz_tool_uses_terminal_event_without_provider_stream(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "QUIZ_TYPE_SELECTED",
        "payload": {"quizType": "MCQ"},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["quizContext"] = {
        "coverage": {"startPage": 1, "endPage": 3},
        "pages": [
            {"pageNumber": 1, "text": "1페이지"},
            {"pageNumber": 2, "text": "2페이지"},
            {"pageNumber": 3, "text": "3페이지"},
        ],
    }
    fake_llm.queue(
        make_quiz(QuizType.MCQ).model_copy(
            update={"coverage": QuizCoverage(start_page=1, end_page=3)}
        )
    )

    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )

    events = parse_events(response)
    assert [event["type"] for event in events] == [
        "status",
        "thought_summary",
        "completed",
    ]
    assert events[0]["stage"] == "PLANNING"
    assert events[1]["text"] == "요청을 처리하는 중입니다"
    completed = CompletedStreamEvent.model_validate(events[-1])
    assert completed.result.quiz is not None
    assert completed.result.quiz.quiz_type is QuizType.MCQ
    assert completed.result.quiz.coverage == QuizCoverage(
        start_page=1,
        end_page=3,
    )
    assert len(fake_llm.calls) == 1
    assert fake_llm.stream_calls == []


async def test_quiz_ndjson_emits_status_and_heartbeat_before_completed(
    app: FastAPI,
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    slow_llm = SlowFakeLlm(delay_seconds=0.02)
    service = make_service(
        slow_llm,
        settings,
        heartbeat_interval_seconds=0.005,
    )
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "QUIZ_TYPE_SELECTED",
        "payload": {"quizType": "MCQ"},
    }
    slow_llm.queue(make_quiz(QuizType.MCQ))

    app.dependency_overrides[get_turn_service] = lambda: service
    try:
        response = await client.post(
            "/internal/ai/turn",
            json=payload,
            headers={**auth_headers, "Accept": "application/x-ndjson"},
        )
    finally:
        app.dependency_overrides.pop(get_turn_service, None)

    events = parse_events(response)
    event_types = [event["type"] for event in events]
    assert event_types[:2] == ["status", "thought_summary"]
    assert events[0]["stage"] == "PLANNING"
    assert events[1]["text"] == "요청을 처리하는 중입니다"
    assert "heartbeat" in event_types[2:-1]
    assert event_types[-1] == "completed"


async def test_ndjson_completed_includes_memory_write(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    payload = deepcopy(turn_payload)
    set_temporary_candidates(
        payload,
        [
            temporary_candidate(101, evidence_source_id=501),
            temporary_candidate(102, evidence_source_id=502),
        ],
    )
    memory_write: dict[str, object] = {"candidateIds": [101, 102]}
    fake_llm.queue(
        plan_with_memory_action(
            ToolName.PROMOTE_MEMORY,
            memory_write,
            "ANSWER_AND_PROMOTE_MEMORY",
        )
    )
    fake_llm.queue_text_stream("편차는 평균과 관측값의 차이입니다.")

    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"},
    )

    events = parse_events(response)
    assert events[-1]["type"] == "completed"
    completed = CompletedStreamEvent.model_validate(events[-1])
    raw_result = events[-1]["result"]
    assert isinstance(raw_result, dict)
    assert raw_result["memoryWrite"] == memory_write
    assert completed.result.memory_write == memory_write
    assert completed.result.memory_candidates == []


async def test_ndjson_accept_still_requires_internal_token(
    client: httpx.AsyncClient,
    turn_payload: dict[str, object],
) -> None:
    response = await client.post(
        "/internal/ai/turn",
        json=turn_payload,
        headers={
            "Accept": "application/x-ndjson",
            "X-Trace-Id": "stream-auth-trace",
        },
    )

    assert response.status_code == 401
    assert response.headers["content-type"].startswith("application/json")
    assert response.headers["X-Trace-Id"] == "stream-auth-trace"
    assert response.json()["error"]["category"] == "AUTH"
