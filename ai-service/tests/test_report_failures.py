"""Failure envelope contracts for report generation and report QA."""

import httpx

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError
from tests.fakes import FakeLlm
from tests.test_report_contract import generate_payload, query_request


def assert_error_envelope(
    response: httpx.Response,
    *,
    status_code: int,
    code: str,
    category: str,
    retryable: bool,
    trace_id: str | None = None,
) -> None:
    assert response.status_code == status_code
    body = response.json()
    assert "detail" not in body
    assert body["schemaVersion"] == "1.0"
    assert body["error"]["code"] == code
    assert body["error"]["category"] == category
    assert body["error"]["retryable"] is retryable
    if trace_id is not None:
        assert body["traceId"] == trace_id


async def test_report_timeout_returns_gateway_timeout_and_trace_id(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    trace_id = "report-timeout-fixed-trace"
    headers = {**auth_headers, "X-Trace-Id": trace_id}
    fake_llm.queue(
        LlmBridgeError(
            category=ErrorCategory.TIMEOUT,
            retryable=True,
        )
    )

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=headers,
        json=generate_payload(),
    )

    assert_error_envelope(
        response,
        status_code=504,
        code="AI_SERVICE_TIMEOUT",
        category="TIMEOUT",
        retryable=True,
        trace_id=trace_id,
    )
    assert len(fake_llm.calls) == 1


async def test_report_provider_failure_returns_service_unavailable(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(
            category=ErrorCategory.INTERNAL,
            retryable=True,
        )
    )

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=auth_headers,
        json=generate_payload(),
    )

    assert_error_envelope(
        response,
        status_code=503,
        code="AI_SERVICE_UNAVAILABLE",
        category="INTERNAL",
        retryable=True,
    )
    assert len(fake_llm.calls) == 1


async def test_report_generate_invalid_json_twice_returns_schema_error(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
    )

    response = await client.post(
        "/internal/ai/reports/generate",
        headers=auth_headers,
        json=generate_payload(),
    )

    assert_error_envelope(
        response,
        status_code=502,
        code="AI_RESPONSE_INVALID",
        category="SCHEMA",
        retryable=False,
    )
    assert len(fake_llm.calls) == 2


async def test_report_query_invalid_json_twice_returns_schema_error(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    fake_llm.queue(
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
        LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False),
    )

    response = await client.post(
        "/internal/ai/reports/query",
        headers=auth_headers,
        json=query_request().model_dump(mode="json", by_alias=True),
    )

    assert_error_envelope(
        response,
        status_code=502,
        code="AI_RESPONSE_INVALID",
        category="SCHEMA",
        retryable=False,
    )
    assert len(fake_llm.calls) == 2
