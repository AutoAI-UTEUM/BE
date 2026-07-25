"""Internal token middleware contract."""

import httpx
import pytest

from edupilot_ai.core.errors import InternalErrorResponse


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"X-Internal-Token": "wrong-token"},
    ],
)
async def test_turn_rejects_missing_or_mismatched_token(
    client: httpx.AsyncClient,
    turn_payload: dict[str, object],
    headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/turn",
        json=turn_payload,
        headers=headers,
    )

    assert response.status_code == 401
    error = InternalErrorResponse.model_validate(response.json())
    assert error.schemaVersion == "1.0"
    assert error.error.code == "AI_INTERNAL_AUTH_FAILED"
    assert error.error.category == "AUTH"
    assert error.error.retryable is False
    assert error.traceId
    assert "wrong-token" not in response.text
