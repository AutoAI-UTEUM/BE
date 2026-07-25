"""Provider-neutral LLM protocol.

Issue #9 intentionally provides no xAI/Grok HTTP implementation.
"""

from collections.abc import Mapping, Sequence
from typing import Protocol

from pydantic import BaseModel

from edupilot_ai.settings import AgentLlmProfile


class LlmBridge(Protocol):
    """Structured-output interface implemented by future provider adapters."""

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[BaseModel],
        profile: AgentLlmProfile,
    ) -> BaseModel:
        """Return a validated structured response."""
        ...
