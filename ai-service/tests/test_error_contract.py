"""Standard internal error envelope tests."""

import httpx

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
