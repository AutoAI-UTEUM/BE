"""Versioned DTOs for POST /internal/ai/turn."""

from enum import StrEnum
from typing import Any, Literal

from pydantic import Field, PrivateAttr, field_validator, model_validator

from edupilot_ai.models.base import ContractModel, Usage
from edupilot_ai.models.quiz import QuizGeneration, QuizType


class EventType(StrEnum):
    EXPLAIN_CURRENT_PAGE = "EXPLAIN_CURRENT_PAGE"
    USER_QUESTION = "USER_QUESTION"
    QUIZ_TYPE_SELECTED = "QUIZ_TYPE_SELECTED"
    DIAGNOSIS_ANSWER_SUBMITTED = "DIAGNOSIS_ANSWER_SUBMITTED"
    NOTE_REQUESTED = "NOTE_REQUESTED"


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
    include_current_page: bool | None = None
    quiz_type: QuizType | None = None
    diagnosis_id: int | None = Field(default=None, gt=0)
    answer: str | None = Field(default=None, min_length=1)


_PAYLOAD_RULES: dict[EventType, tuple[frozenset[str], frozenset[str]]] = {
    EventType.EXPLAIN_CURRENT_PAGE: (frozenset({"detail_level"}), frozenset()),
    EventType.USER_QUESTION: (
        frozenset({"message"}),
        frozenset({"include_current_page"}),
    ),
    EventType.QUIZ_TYPE_SELECTED: (frozenset({"quiz_type"}), frozenset()),
    EventType.DIAGNOSIS_ANSWER_SUBMITTED: (
        frozenset({"diagnosis_id", "answer"}),
        frozenset(),
    ),
    EventType.NOTE_REQUESTED: (frozenset(), frozenset()),
}


class TurnEvent(ContractModel):
    event_type: EventType
    payload: EventPayload

    @model_validator(mode="after")
    def validate_payload(self) -> TurnEvent:
        required, optional = _PAYLOAD_RULES[self.event_type]
        supplied = self.payload.model_fields_set
        if not required <= supplied or not supplied <= required | optional:
            raise ValueError("payload fields do not match eventType")
        return self


class MemoryEvidenceRef(ContractModel):
    source_type: str = Field(min_length=1)
    source_id: int | None = Field(default=None, gt=0)
    session_id: int = Field(gt=0)
    reference: str | None = Field(default=None, min_length=1)

    @model_validator(mode="after")
    def validate_identity_source(self) -> MemoryEvidenceRef:
        if self.source_id is None and self.reference is None:
            raise ValueError("evidenceRef requires sourceId or reference")
        return self

    def identity(self) -> tuple[str, int | str, int]:
        if self.reference is not None:
            return (self.source_type, self.reference.strip(), self.session_id)
        if self.source_id is None:
            raise ValueError("validated evidenceRef must have an identity")
        return (self.source_type, self.source_id, self.session_id)


class TemporaryMemoryCandidate(ContractModel):
    candidate_id: int = Field(gt=0)
    type: Literal["STRENGTH", "WEAKNESS", "MISCONCEPTION", "PREFERENCE"]
    content: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)
    evidence_refs: list[MemoryEvidenceRef] = Field(min_length=1)


class MemoryContext(ContractModel):
    temporary_candidates: list[TemporaryMemoryCandidate] = Field(
        default_factory=list,
        max_length=10,
    )


class QuizContextCoverage(ContractModel):
    start_page: int = Field(gt=0)
    end_page: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_order(self) -> QuizContextCoverage:
        if self.end_page < self.start_page:
            raise ValueError("quizContext coverage range is reversed")
        return self


class QuizContextPage(ContractModel):
    page_number: int = Field(gt=0)
    text: str


class QuizContext(ContractModel):
    coverage: QuizContextCoverage
    pages: list[QuizContextPage] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_pages_cover_range(self) -> QuizContext:
        expected_pages = list(range(self.coverage.start_page, self.coverage.end_page + 1))
        actual_pages = [page.page_number for page in self.pages]
        if actual_pages != expected_pages:
            raise ValueError("quizContext pages must uniquely cover the ordered coverage range")
        return self


class ContextSnapshot(ContractModel):
    xai_file_id: str | None = Field(default=None, min_length=1)
    conversation_summary: str | None = None
    current_page_text: str | None
    previous_page_text: str | None
    next_page_text: str | None
    recent_messages: list[dict[str, Any]]
    qa_thread_digest: dict[str, Any] | str | None
    quiz_assessments: list[dict[str, Any]]
    learner_memory_digest: str | None
    learner_level: str | None
    learner_confidence: Literal["LOW", "MEDIUM", "HIGH"] | None
    pending_diagnosis: dict[str, Any] | str | None
    latest_repair: dict[str, Any] | str | None
    memory: MemoryContext
    quiz_context: QuizContext | None = None

    @field_validator("xai_file_id")
    @classmethod
    def normalize_xai_file_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("xaiFileId must not be blank")
        return normalized


class TurnRequest(ContractModel):
    schema_version: Literal["1.0"]
    turn_id: str = Field(min_length=1)
    session: SessionSnapshot
    event: TurnEvent
    context: ContextSnapshot

    @model_validator(mode="after")
    def validate_page_context(self) -> TurnRequest:
        if self.context.current_page_text is None and not (
            self.event.event_type is EventType.USER_QUESTION
            and self.event.payload.include_current_page is False
        ):
            raise ValueError(
                "currentPageText may be null only for USER_QUESTION with includeCurrentPage=false"
            )
        quiz_context = self.context.quiz_context
        if quiz_context is not None:
            if self.event.event_type is not EventType.QUIZ_TYPE_SELECTED:
                raise ValueError("quizContext is allowed only for QUIZ_TYPE_SELECTED")
            if quiz_context.coverage.end_page != self.session.current_page:
                raise ValueError("quizContext coverage.endPage must equal session.currentPage")
        return self


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


class NoteDraft(ContractModel):
    title: str = Field(min_length=1, max_length=60)
    content: str = Field(min_length=1)


class TurnResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    turn_id: str
    turn_goal: str
    actions_executed: list[ActionExecuted]
    messages: list[Message]
    state_patch: dict[str, Any]
    ui_actions: list[dict[str, Any]]
    memory_candidates: list[dict[str, Any]]
    memory_write: dict[str, Any] | None = None
    quiz: QuizGeneration | None = Field(default=None, exclude_if=lambda value: value is None)
    note_draft: NoteDraft | None = Field(default=None, exclude_if=lambda value: value is None)
    usage: Usage | None = None
