"""Minimal context construction from the Spring snapshot."""

from typing import Any

from pydantic import BaseModel, ConfigDict

from edupilot_ai.models.turn import EventPayload, EventType, SessionSnapshot, TurnRequest


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
    learner_confidence: float | None
    pending_diagnosis: dict[str, Any] | str | None
    latest_repair: dict[str, Any] | str | None

    def qa_thread_ref(self) -> str | None:
        if isinstance(self.qa_thread_digest, dict):
            value = self.qa_thread_digest.get("threadRef")
            return value if isinstance(value, str) and value else None
        return None


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
        )
