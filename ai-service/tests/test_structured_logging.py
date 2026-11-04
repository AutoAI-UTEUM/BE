"""Structured logging and sensitive-data exclusion tests."""

import asyncio
import json
import logging
from io import StringIO

import httpx
import pytest
from pydantic import SecretStr
from starlette.types import Message, Receive, Scope, Send

from edupilot_ai.core.logging import LoggingRuntime, bind_log_context, reset_log_context
from edupilot_ai.core.middleware import InternalTokenMiddleware
from edupilot_ai.settings import RuntimeEnvironment
from tests.fakes import FakeLlm
from tests.test_outline import outline_output, outline_payload
from tests.test_report_contract import generate_payload, report_output


@pytest.mark.parametrize("started", [False, True])
async def test_cancelled_request_is_info_without_fake_http_error(started: bool) -> None:
    stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=stream)
    sent: list[Message] = []

    async def application(scope: Scope, receive: Receive, send: Send) -> None:
        if started:
            await send({"type": "http.response.start", "status": 200, "headers": []})
        raise asyncio.CancelledError()

    async def receive() -> Message:
        return {"type": "http.disconnect"}

    async def send(message: Message) -> None:
        sent.append(message)

    middleware = InternalTokenMiddleware(application, expected_token=SecretStr("test-token"))
    scope: Scope = {
        "type": "http",
        "path": "/internal/ai/composer-prediction",
        "headers": [
            (b"x-internal-token", b"test-token"),
            (b"x-trace-id", b"cancel-trace"),
        ],
    }
    outer_tokens = bind_log_context(trace_id="outer-trace")
    try:
        with pytest.raises(asyncio.CancelledError):
            await middleware(scope, receive, send)
        logging.getLogger("edupilot_ai.test").info("after cancellation")
    finally:
        reset_log_context(outer_tokens)
        runtime.close()
    records = [json.loads(line) for line in stream.getvalue().splitlines()]
    assert records[0]["message"] == "internal request cancelled"
    assert records[0]["status"] == "CANCELLED"
    assert records[0]["level"] == "INFO"
    assert records[0]["traceId"] == "cancel-trace"
    assert records[0].get("httpStatus") == (200 if started else None)
    assert "errorCode" not in records[0]
    assert records[1]["traceId"] == "outer-trace"
    assert len(sent) == int(started)


async def test_real_unexpected_failure_still_logs_error_500() -> None:
    stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=stream)

    async def application(scope: Scope, receive: Receive, send: Send) -> None:
        raise RuntimeError("private-exception-marker")

    async def receive() -> Message:
        return {"type": "http.disconnect"}

    async def send(message: Message) -> None:
        pytest.fail("middleware must not manufacture a response")

    middleware = InternalTokenMiddleware(application, expected_token=SecretStr("test-token"))
    try:
        with pytest.raises(RuntimeError, match="private-exception-marker"):
            await middleware({"type": "http", "path": "/health", "headers": []}, receive, send)
    finally:
        runtime.close()
    record = json.loads(stream.getvalue())
    assert record["message"] == "internal request completed"
    assert record["level"] == "ERROR"
    assert record["status"] == 500
    assert record["errorCode"] == "AI_INTERNAL_ERROR"
    assert "private-exception-marker" not in stream.getvalue()


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
                "exceptionType": "RemoteProtocolError",
                "cleanupTimeoutSeconds": 1.0,
                "deltaChars": 120,
                "terminalChars": 135,
                "recoveredChars": 15,
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
    assert record["exceptionType"] == "RemoteProtocolError"
    assert record["cleanupTimeoutSeconds"] == 1.0
    assert record["deltaChars"] == 120
    assert record["terminalChars"] == 135
    assert record["recoveredChars"] == 15
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


@pytest.mark.parametrize(
    "field",
    [
        "pageCount",
        "meaningfulPageCount",
        "sectionCount",
        "materialCount",
        "successCount",
        "failedCount",
        "documentCount",
        "contextChars",
        "truncatedDocumentCount",
        "summaryChars",
        "correctedScoreStatusCount",
        "droppedMisconceptionCount",
        "examId",
        "questionCount",
        "pageContextCount",
    ],
)
def test_json_log_preserves_feature_counts_without_raw_content(field: str) -> None:
    stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=stream)
    try:
        logging.getLogger("edupilot_ai.test").warning(
            "feature validation failed",
            extra={
                field: 2,
                "errorCode": "SCHEMA",
                "fileName": "private-file-marker.pdf",
                "text": "private-text-marker",
                "answer": "private-answer-marker",
                "fact": "private-fact-marker",
                "imageBase64": "private-image-marker",
            },
        )
    finally:
        runtime.close()

    record = json.loads(stream.getvalue())
    assert record[field] == 2
    assert record["errorCode"] == "SCHEMA"
    assert "private-" not in stream.getvalue()
    assert set(record) == {
        "timestamp",
        "level",
        "service",
        "environment",
        "traceId",
        "message",
        field,
        "errorCode",
    }


async def test_outline_retry_counts_reach_serialized_logs(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(outline_output(overlapping=True), outline_output())
    stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=stream)
    try:
        response = await client.post(
            "/internal/ai/outline", headers=auth_headers, json=outline_payload()
        )
    finally:
        runtime.close()

    assert response.status_code == 200
    records = [json.loads(line) for line in stream.getvalue().splitlines()]
    failure = next(
        item for item in records if item["message"] == "outline output validation failed"
    )
    assert failure["pageCount"] == 3
    assert failure["sectionCount"] == 2
    assert failure["errorCode"] == "SECTION_OVERLAP"
    assert failure["attempt"] == 1
    assert failure["traceId"] == "contract-test-trace"
    success = next(item for item in records if item["message"] == "outline generated")
    assert success["pageCount"] == 3
    assert success["sectionCount"] == 2
    assert "객체의 상태와 행동" not in stream.getvalue()


async def test_report_normalization_reasons_reach_serialized_logs(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(report_output(score=None, misconception_evidence=["ev-1"]))
    stream = StringIO()
    runtime = LoggingRuntime(environment=RuntimeEnvironment.PROD, stream=stream)
    try:
        response = await client.post(
            "/internal/ai/reports/generate", headers=auth_headers, json=generate_payload()
        )
    finally:
        runtime.close()

    assert response.status_code == 200
    assert len(fake_llm.calls) == 1
    records = [json.loads(line) for line in stream.getvalue().splitlines()]
    record = next(item for item in records if item["message"] == "report output normalized")
    assert record["normalizedReasons"] == ["MISCONCEPTION_SINGLE_EVIDENCE", "SCORE_STATUS_CONFLICT"]
    assert record["correctedScoreStatusCount"] == 1
    assert record["droppedMisconceptionCount"] == 1
    assert record["traceId"] == "contract-test-trace"
    assert "정답률 80%" not in stream.getvalue()
    assert "편차 정의를 구체적으로 질문함" not in stream.getvalue()
