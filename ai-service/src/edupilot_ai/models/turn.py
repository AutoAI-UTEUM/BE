"""Versioned DTOs for POST /internal/ai/turn."""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

SCHEMA_VERSION = "1.0"


class ContractModel(BaseModel):
    """Base model for strict camelCase internal contracts."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class TurnRequest(ContractModel):
    """Top-level minimum turn snapshot supplied by Spring."""

    schema_version: Literal["1.0"]
    turn_id: str = Field(min_length=1)
    session: dict[str, Any]
    event: dict[str, Any]
    context: dict[str, Any]


class ActionExecuted(ContractModel):
    """One action selected and executed by the orchestrator."""

    action_id: str
    agent: str
    status: str


class Message(ContractModel):
    """User-visible or system turn message."""

    message_type: Literal["EXPLANATION", "QA", "DIAGNOSIS", "REPAIR", "SYSTEM"]
    content: str


class Usage(ContractModel):
    """Provider usage metadata adopted by contract v0.4."""

    model: str
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    reasoning_tokens: int | None = Field(default=None, ge=0)


class TurnResponse(ContractModel):
    """Issue #9 minimum response with the v0.4 usage field."""

    schema_version: Literal["1.0"] = "1.0"
    turn_id: str
    turn_goal: str
    actions_executed: list[ActionExecuted]
    messages: list[Message]
    state_patch: dict[str, Any]
    ui_actions: list[dict[str, Any]]
    memory_candidates: list[dict[str, Any]]
    usage: Usage
