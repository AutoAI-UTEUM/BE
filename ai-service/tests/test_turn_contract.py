"""POST /internal/ai/turn stub contract."""

import httpx

from edupilot_ai.models.turn import TurnResponse
from tests.fakes import FakeLlm


async def test_turn_returns_v04_minimum_contract_without_llm_call(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
) -> None:
    response = await client.post(
        "/internal/ai/turn",
        json=turn_payload,
        headers=auth_headers,
    )

    assert response.status_code == 200
    turn = TurnResponse.model_validate(response.json())
    assert turn.schema_version == "1.0"
    assert turn.turn_id == "turn-123"
    assert turn.turn_goal == "ANSWER_USER_QUESTION"
    assert turn.actions_executed == []
    assert turn.state_patch == {}
    assert turn.ui_actions == []
    assert turn.memory_candidates == []
    assert turn.usage.model == "grok-4.5"
    assert turn.usage.input_tokens == 0
    assert turn.usage.output_tokens == 0
    assert turn.usage.reasoning_tokens is None
    assert response.headers["X-Trace-Id"] == "contract-test-trace"
    assert fake_llm.calls == []
