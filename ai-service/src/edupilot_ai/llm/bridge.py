"""Provider-neutral structured-output LLM protocol."""

from collections.abc import AsyncIterator, Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Protocol, TypeVar

from pydantic import BaseModel

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.settings import AgentLlmProfile

ModelT = TypeVar("ModelT", bound=BaseModel)
type LlmMessage = Mapping[str, Any]


@dataclass(frozen=True, slots=True)
class LlmUsage:
    """Provider usage retained for the v0.4 turn response."""

    model: str
    input_tokens: int
    output_tokens: int
    reasoning_tokens: int | None


@dataclass(frozen=True, slots=True)
class LlmFileAttachment:
    """Provider-neutral reference to one previously uploaded private file."""

    file_id: str


@dataclass(frozen=True, slots=True)
class LlmCompletion[OutputT: BaseModel]:
    """Validated structured output and its provider metadata."""

    output: OutputT
    usage: LlmUsage


@dataclass(frozen=True, slots=True)
class LlmTextDelta:
    """One provider text delta safe to expose as learner-facing Markdown."""

    text: str


@dataclass(frozen=True, slots=True)
class LlmTextStreamCompleted:
    """Terminal provider metadata for one text stream."""

    usage: LlmUsage


type LlmTextStreamItem = LlmTextDelta | LlmTextStreamCompleted


class LlmBridgeError(Exception):
    """Provider-neutral classified LLM failure."""

    def __init__(
        self,
        *,
        category: ErrorCategory,
        retryable: bool,
        usage: LlmUsage | None = None,
    ) -> None:
        super().__init__(category.value)
        self.category = category
        self.retryable = retryable
        self.usage = usage


class LlmBridge(Protocol):
    """Structured-output interface implemented by provider adapters."""

    async def complete_json(
        self,
        *,
        messages: Sequence[LlmMessage],
        response_model: type[ModelT],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment] = (),
    ) -> LlmCompletion[ModelT]:
        """Return validated structured output plus provider usage."""
        ...

    def complete_text_stream(
        self,
        *,
        messages: Sequence[LlmMessage],
        profile: AgentLlmProfile,
        timeout_seconds: float,
        attachments: Sequence[LlmFileAttachment] = (),
    ) -> AsyncIterator[LlmTextStreamItem]:
        """Yield Markdown deltas followed by exactly one usage item."""
        ...
