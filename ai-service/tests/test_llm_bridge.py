"""LlmBridge protocol and profile configuration tests."""

from pydantic import BaseModel

from edupilot_ai.llm.bridge import LlmBridge
from edupilot_ai.settings import AgentLlmProfile, ReasoningEffort, Settings
from tests.fakes import FakeLlm


class ExampleResponse(BaseModel):
    answer: str


def accepts_bridge(_bridge: LlmBridge) -> None:
    """Static type assertion used by mypy."""


async def test_fake_llm_satisfies_protocol_and_uses_profile(settings: Settings) -> None:
    expected = ExampleResponse(answer="stub")
    fake = FakeLlm([expected])
    accepts_bridge(fake)

    profile = settings.agent_llm_profile
    result = await fake.complete_json(
        messages=[{"role": "user", "content": "test"}],
        response_model=ExampleResponse,
        profile=profile,
    )

    assert result is expected
    assert profile == AgentLlmProfile(
        model="grok-4.5",
        reasoning_effort=ReasoningEffort.MEDIUM,
        max_tokens=16_384,
        temperature=None,
    )
    assert profile.model_dump(by_alias=True) == {
        "model": "grok-4.5",
        "reasoningEffort": "medium",
        "maxTokens": 16_384,
        "temperature": None,
    }
