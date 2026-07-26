"""Provider-neutral structured-output LLM protocol."""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol, TypeVar

from pydantic import BaseModel

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.settings import AgentLlmProfile

ModelT = TypeVar("ModelT", bound=BaseModel)


@dataclass(frozen=True, slots=True)
class LlmUsage:
    """Provider usage retained for the v0.4 turn response."""

    model: str
    input_tokens: int
    output_tokens: int
    reasoning_tokens: int | None


@dataclass(frozen=True, slots=True)
class LlmCompletion[OutputT: BaseModel]:
    """Validated structured output and its provider metadata."""

    output: OutputT
    usage: LlmUsage


class LlmBridgeError(Exception):
    """Provider-neutral classified LLM failure."""

    def __init__(
        self,
        *,
        category: ErrorCategory,
        retryable: bool,
    ) -> None:
        super().__init__(category.value)
        self.category = category
        self.retryable = retryable


class LlmBridge(Protocol):
    """Structured-output interface implemented by provider adapters."""

    async def complete_json(
        self,
        *,
        messages: Sequence[Mapping[str, str]],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
    ) -> LlmCompletion[ModelT]:
        """Return validated structured output plus provider usage."""
        ...
