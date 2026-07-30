"""Structured logging and sensitive-data exclusion tests."""

import json
import logging
from io import StringIO

import httpx
import pytest

from edupilot_ai.core.logging import LoggingRuntime, bind_log_context, reset_log_context
from edupilot_ai.settings import RuntimeEnvironment


def test_json_log_contains_correlation_and_action_fields() -> None:
    stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.DEV, stream=stream)
    tokens = bind_log_context(
        trace_id="trace-json-test",
        turn_id="turn-json-test",
        action_id="action-json-test",
    )
    try:
        logging.getLogger("edupilot_ai.test").info(
            "tool action completed",
            extra={
                "agent": "QaAgent",
                "tool": "ANSWER_QUESTION",
                "status": "SUCCESS",
                "durationMs": 12.5,
            },
        )
    finally:
        reset_log_context(tokens)
        runtime.close()

    record = json.loads(stream.getvalue())
    assert record["service"] == "ai-service"
    assert record["environment"] == "dev"
    assert record["traceId"] == "trace-json-test"
    assert record["turnId"] == "turn-json-test"
    assert record["actionId"] == "action-json-test"
    assert record["agent"] == "QaAgent"
    assert record["tool"] == "ANSWER_QUESTION"
    assert record["status"] == "SUCCESS"
    assert record["durationMs"] == 12.5
    assert record["timestamp"].endswith("+00:00")


def test_environment_log_levels_follow_contract() -> None:
    dev_stream = StringIO()
    dev_runtime = LoggingRuntime(
        environment=RuntimeEnvironment.DEV,
        stream=dev_stream,
    )
    try:
        logger = logging.getLogger("edupilot_ai.level_test")
        logger.debug("not emitted")
        logger.info("emitted")
    finally:
        dev_runtime.close()
    assert "not emitted" not in dev_stream.getvalue()
    assert "emitted" in dev_stream.getvalue()

    local_stream = StringIO()
    local_runtime = LoggingRuntime(
        environment=RuntimeEnvironment.LOCAL,
        stream=local_stream,
    )
    try:
        logging.getLogger("edupilot_ai.level_test").debug("local debug")
    finally:
        local_runtime.close()
    assert "local debug" in local_stream.getvalue()


async def test_request_log_reuses_incoming_trace_id(
    client: httpx.AsyncClient,
    caplog: pytest.LogCaptureFixture,
) -> None:
    middleware_logger = logging.getLogger("edupilot_ai.core.middleware")
    middleware_logger.addHandler(caplog.handler)
    try:
        response = await client.get(
            "/health",
            headers={"X-Trace-Id": "spring-trace-id"},
        )
    finally:
        middleware_logger.removeHandler(caplog.handler)

    assert response.status_code == 200
    assert response.headers["X-Trace-Id"] == "spring-trace-id"
    request_log = next(
        record for record in caplog.records if record.message == "internal request completed"
    )
    assert request_log.__dict__["traceId"] == "spring-trace-id"
    assert request_log.__dict__["endpoint"] == "/health"
    assert request_log.__dict__["status"] == 200
