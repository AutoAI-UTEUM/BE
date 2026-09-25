"""Turn timing keeps API contracts, planning choices and cancellation intact."""

import json
import logging
from collections.abc import AsyncGenerator
from copy import deepcopy

import httpx
import pytest

from edupilot_ai.api.turn import _logged_turn_stream
from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError
from edupilot_ai.models.plan import AgentOutput, ToolName
from edupilot_ai.models.quiz import QuizType
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.service import TurnService
from tests.fakes import FakeLlm
from tests.planner_fixtures import planner_output
from tests.test_llm_observability import TestClock, metric_records
from tests.test_quiz_grading import make_quiz
from tests.test_turn_contract import make_plan


class TimedTurnService(TurnService):
    def __init__(self, clock: TestClock, *, fail: bool = False, content: bool = True) -> None:
        self.clock = clock
        self.closed = False
        self.fail = fail
        self.content = content

    async def stream_ndjson(self, turn: TurnRequest) -> AsyncGenerator[str]:
        frames: list[tuple[float, dict[str, object]]] = [
            (1, {"type": "status", "stage": "PLANNING"}),
            (2, {"type": "thought_summary", "text": "처리 중"}),
            (3, {"type": "heartbeat"}),
        ]
        if self.content:
            frames.extend(
                [
                    (4, {"type": "content_delta", "text": " "}),
                    (5, {"type": "content_delta", "text": "PRIVATE-FIRST"}),
                    (7, {"type": "content_delta", "text": "PRIVATE-LAST"}),
                ]
            )
        frames.append((9, {"type": "status", "stage": "FINALIZING"}))
        frames.append(
            (
                10,
                (
                    {"type": "error", "code": "AI_RESPONSE_INVALID"}
                    if self.fail
                    else {"type": "completed", "result": {}}
                ),
            )
        )
        try:
            for elapsed, event in frames:
                self.clock.now = 1000 + elapsed
                yield json.dumps(event) + "\n"
        finally:
            self.closed = True


@pytest.mark.parametrize("outcome", ["success", "error", "cancel"])
async def test_turn_times_ignore_control_events_and_keep_partial_times_on_failure(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    turn_payload: dict[str, object],
    outcome: str,
) -> None:
    clock = TestClock()
    monkeypatch.setattr("edupilot_ai.api.turn.perf_counter", clock)
    monkeypatch.setattr("edupilot_ai.core.observability.perf_counter", clock)
    caplog.set_level(logging.INFO, logger="edupilot_ai.api.turn")
    service = TimedTurnService(clock, fail=outcome == "error")
    stream = _logged_turn_stream(service, TurnRequest.model_validate(turn_payload))
    received = []
    async for chunk in stream:
        received.append(json.loads(chunk))
        if outcome == "cancel" and received[-1].get("text") == "PRIVATE-LAST":
            await stream.aclose()
            break
    assert service.closed
    first = metric_records(caplog, "turn first content available")
    assert len(first) == 1
    assert first[0]["firstContentMs"] == 5000
    final_message, status = {
        "success": ("turn stream completed", "SUCCESS"),
        "error": ("turn stream failed", "FAILED"),
        "cancel": ("turn stream cancelled by client", "CANCELLED"),
    }[outcome]
    record = metric_records(caplog, final_message)[0]
    assert record["status"] == status
    assert record["firstContentMs"] == 5000
    assert record["lastContentMs"] == 7000
    assert record["contentSpanMs"] == 2000
    assert record["durationMs"] == (7000 if outcome == "cancel" else 10000)
    assert record["eventType"] == "USER_QUESTION"
    assert record["streaming"] is True
    if outcome == "success":
        assert record["resultReadyMs"] == 10000
        assert received[-1]["type"] == "completed"
    else:
        assert "resultReadyMs" not in record
    if outcome == "cancel":
        assert record["level"] == "INFO"
        assert not metric_records(caplog, "turn stream failed unexpectedly")
    assert "PRIVATE-FIRST" not in caplog.text
    assert "PRIVATE-LAST" not in caplog.text
    # Metrics are logs only; neither timing fields nor metadata are added to NDJSON.
    assert all("firstContentMs" not in event and "streaming" not in event for event in received)


async def test_no_content_stream_records_only_result_ready_time(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    turn_payload: dict[str, object],
) -> None:
    clock = TestClock()
    monkeypatch.setattr("edupilot_ai.api.turn.perf_counter", clock)
    caplog.set_level(logging.INFO, logger="edupilot_ai.api.turn")
    service = TimedTurnService(clock, content=False)
    events = [
        json.loads(chunk)
        async for chunk in _logged_turn_stream(service, TurnRequest.model_validate(turn_payload))
    ]
    record = metric_records(caplog, "turn stream completed")[0]
    assert "firstContentMs" not in record
    assert "lastContentMs" not in record
    assert record["resultReadyMs"] == 10000
    assert not metric_records(caplog, "turn first content available")
    assert events[-1]["type"] == "completed"


@pytest.mark.parametrize("path", ["deterministic", "llm", "schema_retry", "failed"])
@pytest.mark.parametrize("streaming", [False, True])
async def test_planning_logs_distinguish_synthesis_and_schema_regeneration(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    caplog: pytest.LogCaptureFixture,
    path: str,
    streaming: bool,
) -> None:
    payload = deepcopy(turn_payload)
    if path == "deterministic":
        payload["event"] = {
            "eventType": "EXPLAIN_CURRENT_PAGE",
            "payload": {"detailLevel": "NORMAL"},
        }
    else:
        if path in {"schema_retry", "failed"}:
            fake_llm.queue(LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False))
        if path == "failed":
            fake_llm.queue(LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False))
        else:
            fake_llm.queue(
                planner_output(
                    make_plan(
                        ToolName.ANSWER_QUESTION,
                        {"qaThreadMode": "START_NEW", "threadRef": None},
                        "PRIVATE-PLAN-GOAL",
                    )
                )
            )
    if path != "failed":
        if streaming:
            fake_llm.queue_text_stream("PRIVATE-ANSWER")
        else:
            fake_llm.queue(AgentOutput(markdown="PRIVATE-ANSWER"))
    logger = logging.getLogger("edupilot_ai")
    logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/turn",
            json=payload,
            headers={
                **auth_headers,
                "Accept": "application/x-ndjson" if streaming else "application/json",
            },
        )
    finally:
        logger.removeHandler(caplog.handler)
    assert response.status_code == (502 if path == "failed" and not streaming else 200)
    finished = metric_records(caplog, "turn planning finished")[0]
    assert finished["planSource"] == ("DETERMINISTIC" if path == "deterministic" else "LLM")
    assert finished["status"] == ("FAILED" if path == "failed" else "SUCCESS")
    assert finished["eventType"] == (
        "EXPLAIN_CURRENT_PAGE" if path == "deterministic" else "USER_QUESTION"
    )
    assert finished["durationMs"] >= 0
    if path == "failed":
        assert finished["errorCode"] == "SCHEMA"
        assert len(fake_llm.calls) == 2
    else:
        attempts = {"deterministic": 0, "llm": 1, "schema_retry": 2}[path]
        assert finished["plannerAttempts"] == attempts
        assert len(fake_llm.calls) == attempts + int(not streaming)
        policy = metric_records(caplog, "turn policy verification finished")[0]
        assert policy["status"] == "SUCCESS"
        assert policy["durationMs"] >= 0
    retries = metric_records(caplog, "planner attempt failed")
    if path == "schema_retry":
        assert len(retries) == 1
        assert retries[0]["status"] == "RETRYING"
    if not streaming:
        assert "firstContentMs" not in response.json()
    assert "PRIVATE-PLAN-GOAL" not in caplog.text
    assert "PRIVATE-ANSWER" not in caplog.text


async def test_real_quiz_turn_has_readiness_but_no_fake_first_content(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    caplog: pytest.LogCaptureFixture,
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {"eventType": "QUIZ_TYPE_SELECTED", "payload": {"quizType": "MCQ"}}
    fake_llm.queue(make_quiz(QuizType.MCQ))
    logger = logging.getLogger("edupilot_ai")
    logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/turn",
            json=payload,
            headers={**auth_headers, "Accept": "application/x-ndjson"},
        )
    finally:
        logger.removeHandler(caplog.handler)
    events = [json.loads(line) for line in response.text.splitlines()]
    assert events[-1]["type"] == "completed"
    assert events[-1]["result"]["quiz"] is not None
    assert len(fake_llm.calls) == 1
    record = metric_records(caplog, "turn stream completed")[0]
    assert record["eventType"] == "QUIZ_TYPE_SELECTED"
    assert record["resultReadyMs"] <= record["durationMs"]
    assert "firstContentMs" not in record
    assert not metric_records(caplog, "turn first content available")
