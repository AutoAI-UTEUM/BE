"""NDJSON turn stream contract and timeout budget tests."""

import asyncio
import gc
import json
import logging
import time
from collections.abc import AsyncGenerator, AsyncIterator, Mapping, Sequence
from copy import deepcopy
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from starlette.types import Message as AsgiMessage
from starlette.types import Scope

from edupilot_ai.api.deps import get_turn_service
from edupilot_ai.api.turn import execute_turn
from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmCompletion,
    LlmFileAttachment,
    LlmPromptCache,
    LlmTextDelta,
    LlmTextStreamItem,
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
from tests.planner_fixtures import planner_output
from tests.test_learning_support import (
    plan_with_memory_action,
    set_temporary_candidates,
    temporary_candidate,
)
from tests.test_quiz_grading import make_quiz
from tests.test_turn_contract import make_explain_plan


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
        prompt_cache: LlmPromptCache | None = None,
    ) -> LlmCompletion[ModelT]:
        await asyncio.sleep(self._delay_seconds)
        return await super().complete_json(
            messages=messages,
            response_model=response_model,
            profile=profile,
            timeout_seconds=timeout_seconds,
            attachments=attachments,
            prompt_cache=prompt_cache,
        )


class DisconnectAwareFakeLlm(FakeLlm):
    def __init__(self) -> None:
        super().__init__()
        self.stream_closed = asyncio.Event()

    async def complete_text_stream(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment] = (),
        prompt_cache: LlmPromptCache | None = None,
    ) -> AsyncIterator[LlmTextStreamItem]:
        self.stream_calls.append((messages, profile, timeout_seconds))
        self.stream_file_attachments.append(tuple(attachments))
        self.stream_prompt_caches.append(prompt_cache)
        try:
            yield LlmTextDelta(text="첫 델타")
            await asyncio.Event().wait()
        finally:
            self.stream_closed.set()


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
    caplog: pytest.LogCaptureFixture,
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "DETAILED"},
    }
    context = payload["context"]
    assert isinstance(context, dict)
    context["xaiFileId"] = "file-explain-stream"
    fake_llm.queue_completion(
        planner_output(make_explain_plan(propose_quiz=True)),
        LlmUsage("grok-4.5-live", 4, 2, 7),
    )
    fake_llm.queue_text_stream(
        "편차는 ",
        "**평균과 관측값의 차이**입니다.",
        usage=LlmUsage("grok-4.5-live", 12, 8, 2),
    )

    turn_logger = logging.getLogger("edupilot_ai.api.turn")
    caplog.set_level(logging.INFO, logger=turn_logger.name)
    turn_logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/turn",
            json=payload,
            headers={**auth_headers, "Accept": "application/x-ndjson"},
        )
    finally:
        turn_logger.removeHandler(caplog.handler)

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
    assert completed.ui_actions == [
        {
            "type": "BINARY_DECISION",
            "content": "퀴즈를 진행할까요?",
            "yesEvent": "SHOW_QUIZ_TYPE_SELECT",
            "noEvent": "WAIT",
        }
    ]
    assert completed.usage is not None
    assert completed.usage.model == "grok-4.5-live"
    assert completed.usage.input_tokens == 16
    assert completed.usage.output_tokens == 10
    assert completed.usage.reasoning_tokens == 9
    assert len(fake_llm.calls) == 1
    assert len(fake_llm.stream_calls) == 1
    assert 0 < fake_llm.stream_calls[0][2] <= 180
    stream_system_prompt = fake_llm.stream_calls[0][0][0]["content"]
    assert "Return only the learner-facing Markdown explanation." in stream_system_prompt
    assert "모든 학습자 대상 텍스트" in stream_system_prompt
    assert [item.file_id for item in fake_llm.stream_file_attachments[0]] == ["file-explain-stream"]
    assert [item.file_id for item in fake_llm.file_attachments[0]] == ["file-explain-stream"]
    completed_log = next(
        record for record in caplog.records if record.message == "turn stream completed"
    )
    assert completed_log.levelno == logging.INFO
    assert completed_log.__dict__["status"] == "SUCCESS"


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
        planner_output(
            make_plan(
                ToolName.ANSWER_QUESTION,
                {"qaThreadMode": "FOLLOW_UP", "threadRef": "qa-11"},
                "ANSWER_FOLLOW_UP",
            )
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
    caplog: pytest.LogCaptureFixture,
) -> None:
    fake_llm.queue(
        planner_output(
            make_plan(
                ToolName.ANSWER_QUESTION,
                {"qaThreadMode": "START_NEW", "threadRef": None},
                "ANSWER_USER_QUESTION",
            )
        )
    )
    fake_llm.queue_text_stream(
        "확정되지 않은 일부 문장",
        LlmBridgeError(category=ErrorCategory.TIMEOUT, retryable=True),
    )

    turn_logger = logging.getLogger("edupilot_ai.api.turn")
    caplog.set_level(logging.INFO, logger=turn_logger.name)
    turn_logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/turn",
            json=turn_payload,
            headers={**auth_headers, "Accept": "application/x-ndjson"},
        )
    finally:
        turn_logger.removeHandler(caplog.handler)

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
    failed_log = next(record for record in caplog.records if record.message == "turn stream failed")
    assert failed_log.levelno == logging.WARNING
    assert failed_log.__dict__["status"] == "FAILED"
    assert failed_log.__dict__["errorCode"] == "AI_SERVICE_TIMEOUT"


async def test_expired_turn_budget_stops_before_agent_call(
    fake_llm: FakeLlm,
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    readings = iter([0.0, 1.0, 181.0])
    service = make_service(fake_llm, settings, clock=lambda: next(readings))
    fake_llm.queue(
        planner_output(
            make_plan(
                ToolName.ANSWER_QUESTION,
                {"qaThreadMode": "START_NEW", "threadRef": None},
                "ANSWER_USER_QUESTION",
            )
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


async def test_heartbeat_close_retrieves_completed_pending_failure() -> None:
    release_failure = asyncio.Event()
    source_closed = asyncio.Event()
    loop_errors: list[dict[str, Any]] = []
    loop = asyncio.get_running_loop()
    previous_handler = loop.get_exception_handler()

    async def failing_events() -> AsyncGenerator[TurnStreamEvent]:
        yield StatusStreamEvent(stage="PLANNING")
        try:
            await release_failure.wait()
            raise RuntimeError("pending source failed")
        finally:
            source_closed.set()

    def collect_loop_error(
        _loop: asyncio.AbstractEventLoop,
        context: dict[str, Any],
    ) -> None:
        loop_errors.append(context)

    stream = events_with_heartbeat(
        failing_events(),
        first_event_timeout_seconds=0.01,
        heartbeat_interval_seconds=0.005,
    )
    loop.set_exception_handler(collect_loop_error)
    try:
        assert (await anext(stream)).type == "status"
        assert (await anext(stream)).type == "heartbeat"
        release_failure.set()
        await asyncio.wait_for(source_closed.wait(), timeout=1)
        await asyncio.sleep(0)

        await stream.aclose()
        del stream
        gc.collect()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
    finally:
        loop.set_exception_handler(previous_handler)

    assert loop_errors == []


async def test_client_disconnect_closes_turn_and_llm_streams_without_task_errors(
    caplog: pytest.LogCaptureFixture,
    settings: Settings,
    turn_payload: dict[str, object],
) -> None:
    disconnecting_llm = DisconnectAwareFakeLlm()
    service = make_service(
        disconnecting_llm,
        settings,
        heartbeat_interval_seconds=0.005,
    )
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "EXPLAIN_CURRENT_PAGE",
        "payload": {"detailLevel": "NORMAL"},
    }
    turn = TurnRequest.model_validate(payload)
    scope: Scope = {
        "type": "http",
        "asgi": {"version": "3.0", "spec_version": "2.3"},
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/internal/ai/turn",
        "raw_path": b"/internal/ai/turn",
        "query_string": b"",
        "root_path": "",
        "headers": [(b"accept", b"application/x-ndjson")],
        "client": ("127.0.0.1", 12345),
        "server": ("test", 80),
    }
    response = await execute_turn(Request(scope), turn, service)
    assert isinstance(response, StreamingResponse)

    disconnect = asyncio.Event()
    received_events: list[dict[str, object]] = []
    loop_errors: list[dict[str, Any]] = []
    loop = asyncio.get_running_loop()
    previous_handler = loop.get_exception_handler()

    async def receive() -> AsgiMessage:
        await disconnect.wait()
        return {"type": "http.disconnect"}

    async def send(message: AsgiMessage) -> None:
        if message["type"] != "http.response.body":
            return
        body = bytes(message.get("body", b""))
        if not body:
            return
        event = json.loads(body)
        received_events.append(event)
        if event.get("type") == "heartbeat":
            disconnect.set()
            await asyncio.sleep(0)

    def collect_loop_error(
        _loop: asyncio.AbstractEventLoop,
        context: dict[str, Any],
    ) -> None:
        loop_errors.append(context)

    loop.set_exception_handler(collect_loop_error)
    try:
        with caplog.at_level(logging.INFO, logger="edupilot_ai.api.turn"):
            async with asyncio.timeout(2):
                await response(scope, receive, send)
        closed_on_return = disconnecting_llm.stream_closed.is_set()
        del response
        gc.collect()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
    finally:
        loop.set_exception_handler(previous_handler)

    assert any(event["type"] == "content_delta" for event in received_events)
    assert any(event["type"] == "heartbeat" for event in received_events)
    assert closed_on_return
    assert loop_errors == []
    cancelled_logs = [
        record
        for record in caplog.records
        if record.name == "edupilot_ai.api.turn"
        and record.message == "turn stream cancelled by client"
    ]
    assert len(cancelled_logs) == 1
    assert cancelled_logs[0].levelno == logging.INFO
    assert cancelled_logs[0].__dict__["status"] == "CANCELLED"
    assert cancelled_logs[0].__dict__["turnId"] == turn.turn_id
    assert not any(
        record.levelno >= logging.ERROR
        for record in caplog.records
        if record.name == "edupilot_ai.api.turn"
    )
    assert not any(
        record.message in {"turn stream completed", "turn stream failed unexpectedly"}
        for record in caplog.records
        if record.name == "edupilot_ai.api.turn"
    )


async def test_accept_omitted_keeps_json_path(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    fake_llm.queue(
        planner_output(
            make_plan(
                ToolName.ANSWER_QUESTION,
                {"qaThreadMode": "START_NEW", "threadRef": None},
                "ANSWER_USER_QUESTION",
            )
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
        planner_output(
            plan_with_memory_action(
                ToolName.PROMOTE_MEMORY,
                memory_write,
                "ANSWER_AND_PROMOTE_MEMORY",
            )
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
