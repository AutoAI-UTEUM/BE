"""Versioned DTOs for POST /internal/ai/turn."""

from enum import StrEnum
from typing import Any, Literal

from pydantic import Field, PrivateAttr, model_validator

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.quiz import QuizGeneration, QuizType


class EventType(StrEnum):
    EXPLAIN_CURRENT_PAGE = "EXPLAIN_CURRENT_PAGE"
    USER_QUESTION = "USER_QUESTION"
    QUIZ_TYPE_SELECTED = "QUIZ_TYPE_SELECTED"
    DIAGNOSIS_ANSWER_SUBMITTED = "DIAGNOSIS_ANSWER_SUBMITTED"


class DetailLevel(StrEnum):
    NORMAL = "NORMAL"
    DETAILED = "DETAILED"


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
    learner_confidence: Literal["LOW", "MEDIUM", "HIGH"] | None
    pending_diagnosis: dict[str, Any] | str | None
    latest_repair: dict[str, Any] | str | None
    memory: MemoryContext


class TurnRequest(ContractModel):
    schema_version: Literal["1.0"]
    turn_id: str = Field(min_length=1)
    session: SessionSnapshot
    event: TurnEvent
    context: ContextSnapshot


class Adjustment(ContractModel):
    field: str
    from_: Any = Field(alias="from")
    to: Any
    reason: str
    _action_id: str | None = PrivateAttr(default=None)

    def bind_to_action(self, action_id: str) -> Adjustment:
        self._action_id = action_id
        return self

    def belongs_to(self, action_id: str) -> bool:
        return self._action_id == action_id


class ActionExecuted(ContractModel):
    action_id: str
    agent: str
    status: Literal["SUCCESS", "FAILED", "SKIPPED"]
    adjustments: list[Adjustment] = Field(
        default_factory=list,
        exclude_if=lambda value: not value,
    )


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
    quiz: QuizGeneration | None = Field(default=None, exclude_if=lambda value: value is None)
    usage: Usage
