"""Strict contracts for incremental learning conversation summaries."""

from typing import Literal

from pydantic import Field, field_validator

from edupilot_ai.models.base import ContractModel


class ConversationSummaryMessage(ContractModel):
    role: Literal["USER", "ASSISTANT"]
    content: str = Field(min_length=1)

    @field_validator("content")
    @classmethod
    def reject_blank_content(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("message content must not be blank")
        return value


class ConversationSummaryRequest(ContractModel):
    schema_version: Literal["1.0"]
    previous_summary: str | None = None
    messages: list[ConversationSummaryMessage] = Field(min_length=1, max_length=20)


class ConversationSummaryCompletion(ContractModel):
    """Minimal structured output requested from the LLM."""

    summary: str = Field(min_length=1)


class ConversationSummaryResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    summary: str = Field(min_length=1, max_length=1000)
