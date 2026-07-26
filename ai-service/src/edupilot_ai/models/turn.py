"""Versioned DTOs for POST /internal/ai/turn."""

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class EventType(StrEnum):
    EXPLAIN_CURRENT_PAGE = "EXPLAIN_CURRENT_PAGE"
    USER_QUESTION = "USER_QUESTION"
    QUIZ_TYPE_SELECTED = "QUIZ_TYPE_SELECTED"
    DIAGNOSIS_ANSWER_SUBMITTED = "DIAGNOSIS_ANSWER_SUBMITTED"


class DetailLevel(StrEnum):
    NORMAL = "NORMAL"
    DETAILED = "DETAILED"


class QuizType(StrEnum):
    MCQ = "MCQ"
    OX = "OX"
    SHORT = "SHORT"
    ESSAY = "ESSAY"


class QaThreadMode(StrEnum):
    START_NEW = "START_NEW"
    FOLLOW_UP = "FOLLOW_UP"


class SessionSnapshot(ContractModel):
    session_id: int = Field(gt=0)
    user_id: int = Field(gt=0)
    material_id: int = Field(gt=0)
    current_page: int = Field(gt=0)
    page_status: str = Field(min_length=1)


class EventPayload(ContractModel):
    detail_level: DetailLevel | None = None
    message: str | None = Field(default=None, min_length=1)
    quiz_type: QuizType | None = None
    diagnosis_id: int | None = Field(default=None, gt=0)
    answer: str | None = Field(default=None, min_length=1)


class TurnEvent(ContractModel):
    event_type: EventType
    payload: EventPayload

    @model_validator(mode="after")
    def validate_payload(self) -> TurnEvent:
        expected = {
            EventType.EXPLAIN_CURRENT_PAGE: {"detail_level"},
            EventType.USER_QUESTION: {"message"},
            EventType.QUIZ_TYPE_SELECTED: {"quiz_type"},
            EventType.DIAGNOSIS_ANSWER_SUBMITTED: {"diagnosis_id", "answer"},
        }[self.event_type]
        if self.payload.model_fields_set != expected:
            raise ValueError("payload fields do not match eventType")
        return self


class MemoryContext(ContractModel):
    temporary_candidates: list[dict[str, Any]] = Field(default_factory=list)


class ContextSnapshot(ContractModel):
    current_page_text: str
    previous_page_text: str | None
    next_page_text: str | None
    recent_messages: list[dict[str, Any]]
    qa_thread_digest: dict[str, Any] | str | None
    quiz_assessments: list[dict[str, Any]]
    learner_memory_digest: dict[str, Any] | str | None
    learner_level: str | None
    learner_confidence: float | None = Field(ge=0, le=1)
    pending_diagnosis: dict[str, Any] | str | None
    latest_repair: dict[str, Any] | str | None
    memory: MemoryContext


class TurnRequest(ContractModel):
    schema_version: Literal["1.0"]
    turn_id: str = Field(min_length=1)
    session: SessionSnapshot
    event: TurnEvent
    context: ContextSnapshot


class ActionExecuted(ContractModel):
    action_id: str
    agent: str
    status: Literal["SUCCESS", "FAILED", "SKIPPED"]


class Message(ContractModel):
    message_type: Literal["EXPLANATION", "QA", "DIAGNOSIS", "REPAIR", "SYSTEM"]
    content: str


class Usage(ContractModel):
    model: str
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    reasoning_tokens: int | None = Field(default=None, ge=0)


class TurnResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    turn_id: str
    turn_goal: str
    actions_executed: list[ActionExecuted]
    messages: list[Message]
    state_patch: dict[str, Any]
    ui_actions: list[dict[str, Any]]
    memory_candidates: list[dict[str, Any]]
    usage: Usage
