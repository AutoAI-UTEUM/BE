"""Minimal context construction from the Spring snapshot."""

from copy import deepcopy
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from edupilot_ai.models.turn import (
    EventPayload,
    EventType,
    MemoryContext,
    SessionSnapshot,
    TurnRequest,
)

_PLAN_ASSESSMENT_FIELDS = {
    "understandingSummary",
    "recommendedNextDirection",
    "weaknesses",
}


class AgentContext(BaseModel):
    model_config = ConfigDict(frozen=True)

    turn_id: str
    session: SessionSnapshot
    event_type: EventType
    event_payload: EventPayload
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

    def qa_thread_ref(self) -> str | None:
        if isinstance(self.qa_thread_digest, dict):
            value = self.qa_thread_digest.get("threadRef")
            return value if isinstance(value, str) and value else None
        return None


class PlanContextModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        frozen=True,
        populate_by_name=True,
    )


class PlanSession(PlanContextModel):
    current_page: int
    page_status: str


class PlanRecentMessage(PlanContextModel):
    role: str | None
    content: str


class PlanQaThreadDigest(PlanContextModel):
    thread_ref: str | None
    has_summary: bool


class PlanContext(PlanContextModel):
    turn_id: str
    session: PlanSession
    event_type: EventType
    event_payload: dict[str, Any]
    page_text_preview: str
    has_previous_page_text: bool
    has_next_page_text: bool
    recent_messages: list[PlanRecentMessage]
    qa_thread_digest: PlanQaThreadDigest | None
    quiz_assessments: list[dict[str, Any]]
    learner_level: str | None
    learner_confidence: Literal["LOW", "MEDIUM", "HIGH"] | None
    has_pending_diagnosis: bool
    pending_diagnosis_id: int | None
    has_latest_repair: bool
    memory: MemoryContext

    @classmethod
    def from_agent_context(cls, context: AgentContext) -> PlanContext:
        qa_digest = context.qa_thread_digest
        plan_qa_digest: PlanQaThreadDigest | None = None
        if qa_digest is not None:
            thread_ref = context.qa_thread_ref()
            if isinstance(qa_digest, dict):
                summary = qa_digest.get("summary")
            else:
                summary = qa_digest
            plan_qa_digest = PlanQaThreadDigest(
                thread_ref=thread_ref,
                has_summary=isinstance(summary, str) and bool(summary.strip()),
            )

        recent_messages = [
            PlanRecentMessage(
                role=item.get("role") if isinstance(item.get("role"), str) else None,
                content=(
                    item.get("content", "")[:120]
                    if isinstance(item.get("content"), str)
                    else ""
                ),
            )
            for item in context.recent_messages[-3:]
        ]

        pending_diagnosis_id: int | None = None
        if isinstance(context.pending_diagnosis, dict):
            value = context.pending_diagnosis.get("diagnosisId")
            if isinstance(value, int) and not isinstance(value, bool):
                pending_diagnosis_id = value

        event_payload = context.event_payload.model_dump(
            mode="json",
            by_alias=True,
            exclude_none=True,
        )
        event_payload.pop("answer", None)

        quiz_assessments: list[dict[str, Any]] = []
        if context.quiz_assessments:
            latest_assessment = context.quiz_assessments[-1]
            quiz_assessments.append(
                {
                    key: deepcopy(latest_assessment[key])
                    for key in _PLAN_ASSESSMENT_FIELDS
                    if key in latest_assessment
                }
            )
        return cls(
            turn_id=context.turn_id,
            session=PlanSession(
                current_page=context.session.current_page,
                page_status=context.session.page_status,
            ),
            event_type=context.event_type,
            event_payload=event_payload,
            page_text_preview=context.current_page_text[:500],
            has_previous_page_text=context.previous_page_text is not None,
            has_next_page_text=context.next_page_text is not None,
            recent_messages=recent_messages,
            qa_thread_digest=plan_qa_digest,
            quiz_assessments=quiz_assessments,
            learner_level=context.learner_level,
            learner_confidence=context.learner_confidence,
            has_pending_diagnosis=context.pending_diagnosis is not None,
            pending_diagnosis_id=pending_diagnosis_id,
            has_latest_repair=context.latest_repair is not None,
            memory=context.memory.model_copy(deep=True),
        )


class ContextBuilder:
    def build(self, turn: TurnRequest) -> AgentContext:
        context = turn.context
        return AgentContext(
            turn_id=turn.turn_id,
            session=turn.session,
            event_type=turn.event.event_type,
            event_payload=turn.event.payload,
            current_page_text=context.current_page_text,
            previous_page_text=context.previous_page_text,
            next_page_text=context.next_page_text,
            recent_messages=context.recent_messages,
            qa_thread_digest=context.qa_thread_digest,
            quiz_assessments=context.quiz_assessments,
            learner_memory_digest=context.learner_memory_digest,
            learner_level=context.learner_level,
            learner_confidence=context.learner_confidence,
            pending_diagnosis=context.pending_diagnosis,
            latest_repair=context.latest_repair,
            memory=context.memory,
        )
