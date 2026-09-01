"""Strict contracts for lightweight document question answering."""

from typing import Literal

from pydantic import Field, field_validator

from edupilot_ai.models.base import ContractModel, Usage


class DocChatContextDocument(ContractModel):
    title: str = Field(min_length=1)
    text: str = Field(min_length=1)

    @field_validator("title", "text")
    @classmethod
    def reject_blank_values(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("document fields must not be blank")
        return value


class DocChatHistoryMessage(ContractModel):
    role: Literal["USER", "ASSISTANT"]
    content: str = Field(min_length=1)


class DocChatRequest(ContractModel):
    schema_version: Literal["1.0"]
    context_docs: list[DocChatContextDocument] = Field(min_length=1, max_length=10)
    history: list[DocChatHistoryMessage] = Field(max_length=10)
    question: str = Field(min_length=1)

    @field_validator("question")
    @classmethod
    def reject_blank_question(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("question must not be blank")
        return value


class DocChatCompletion(ContractModel):
    """Minimal structured output requested from the LLM."""

    answer: str = Field(min_length=1)


class DocChatWarning(ContractModel):
    type: str = Field(min_length=1)
    message: str = Field(min_length=1)


class DocChatResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    answer: str = Field(min_length=1)
    warnings: list[DocChatWarning] = Field(default_factory=list)
    usage: Usage | None = None
