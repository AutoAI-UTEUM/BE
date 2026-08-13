"""Standard internal error envelope tests."""

import logging

import httpx
import pytest
from fastapi import FastAPI

from edupilot_ai.core.errors import ErrorCategory, InternalErrorResponse


def test_error_categories_match_contract_v04() -> None:
    assert {category.value for category in ErrorCategory} == {
        "AUTH",
        "TIMEOUT",
        "SCHEMA",
        "POLICY",
        "INTERNAL",
    }


async def test_request_validation_uses_schema_error_envelope(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    turn_payload.pop("schemaVersion")
    response = await client.post(
        "/internal/ai/turn",
        json=turn_payload,
        headers=auth_headers,
    )

    assert response.status_code == 422
    error = InternalErrorResponse.model_validate(response.json())
    assert error.model_dump(mode="json").keys() == {"schemaVersion", "error", "traceId"}
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False
    assert error.traceId == "contract-test-trace"
    assert "validation" not in error.error.message.lower()


async def test_unexpected_exception_uses_internal_error_envelope(
    app: FastAPI,
    auth_headers: dict[str, str],
    caplog: pytest.LogCaptureFixture,
) -> None:
    def raise_test_exception() -> None:
        raise RuntimeError("test-only failure")

    app.add_api_route(
        "/internal/test/error",
        raise_test_exception,
        methods=["GET"],
    )

    errors_logger = logging.getLogger("edupilot_ai.core.errors")
    errors_logger.addHandler(caplog.handler)
    try:
        async with app.router.lifespan_context(app):
            transport = httpx.ASGITransport(
                app=app,
                raise_app_exceptions=False,
            )
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://test",
            ) as client:
                response = await client.get(
                    "/internal/test/error",
                    headers=auth_headers,
                )
    finally:
        errors_logger.removeHandler(caplog.handler)

    assert response.status_code == 500
    error = InternalErrorResponse.model_validate(response.json())
    assert error.schemaVersion == "1.0"
    assert error.error.code == "AI_INTERNAL_ERROR"
    assert error.error.category == "INTERNAL"
    assert error.error.retryable is False
    assert error.traceId == "contract-test-trace"
    assert "test-only failure" not in response.text
    record = next(
        item
        for item in caplog.records
        if item.message == "internal API request failed unexpectedly"
    )
    assert record.exc_info is not None
    assert isinstance(record.exc_info[1], RuntimeError)
