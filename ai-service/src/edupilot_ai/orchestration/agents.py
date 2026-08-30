"""Turn agents using injected structured-output LLM."""

import logging
import re
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, Literal

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridge,
    LlmBridgeError,
    LlmFileAttachment,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
)
from edupilot_ai.models.learning_support import RepairOutput
from edupilot_ai.models.plan import AgentOutput
from edupilot_ai.models.quiz import QuizGeneration, QuizType
from edupilot_ai.models.turn import DetailLevel, Message, NoteDraft, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext
from edupilot_ai.orchestration.prompts import (
    explainer_messages,
    note_messages,
    qa_messages,
    quiz_messages,
    repair_messages,
)
from edupilot_ai.orchestration.timing import TurnDeadline
from edupilot_ai.settings import AgentLlmProfile
from edupilot_ai.usage import combine_llm_usages, unknown_llm_usage

_NEXT_PAGE_EXPLAIN = re.compile(
    r"(다음|뒤|뒷)\s*(페이지|장|쪽).{0,12}(설명|알려|보여|가르쳐|넘어가)"
)
_PREV_PAGE_EXPLAIN = re.compile(r"(이전|앞)\s*(페이지|장|쪽).{0,12}(설명|보여|가르쳐)")
_NEXT_PAGE_GUIDANCE = (
    "다음 페이지 내용은 페이지를 이동한 뒤에 설명드릴게요. 아래에서 이동을 선택해 주세요."
)
_PREVIOUS_PAGE_GUIDANCE = "이전 페이지 내용은 해당 페이지로 이동하시면 다시 설명드릴 수 있어요."
_EMPTY_PAGE_EXPLANATION = (
    "이 페이지에는 설명할 텍스트 내용이 없어요. 이미지나 도형 중심 페이지라면 "
    "다음 페이지로 이동해 학습을 이어가 주세요."
)
_NOTE_REQUEST = re.compile(
    r"(?:노트(?!북).{0,20}?(?:정리|작성|만들|남겨)|"
    r"필기.{0,20}?(?:정리|작성|만들|남겨|해\s*줘))",
    re.IGNORECASE,
)
_FORBIDDEN_NOTE_FIELDS = {
    "actionid",
    "criterionkey",
    "pagestatus",
    "sessionid",
    "statepatch",
    "submissionid",
    "turnid",
}
logger = logging.getLogger(__name__)


def _material_attachments(context: AgentContext) -> tuple[LlmFileAttachment, ...]:
    file_id = context.attached_file_id
    return (LlmFileAttachment(file_id=file_id),) if file_id is not None else ()


def detect_page_redirect(message: str) -> Literal["NEXT", "PREVIOUS"] | None:
    """Detect conservative cross-page explanation requests."""

    previous = _PREV_PAGE_EXPLAIN.search(message) is not None
    next_page = _NEXT_PAGE_EXPLAIN.search(message) is not None
    if previous and next_page:
        return None
    if previous:
        return "PREVIOUS"
    if next_page:
        return "NEXT"
    return None


def detect_note_request(message: str) -> bool:
    """Detect explicit learner requests to create or organize a note."""

    return _NOTE_REQUEST.search(message) is not None


@dataclass(frozen=True, slots=True)
class AgentResult:
    agent: str
    message: Message | None
    state_patch: dict[str, Any]
    usage: LlmUsage
    quiz: QuizGeneration | None = None
    memory_candidates: list[dict[str, Any]] = field(default_factory=list)
    ui_actions: list[dict[str, Any]] = field(default_factory=list)
    note_draft: NoteDraft | None = None


@dataclass(frozen=True, slots=True)
class AgentTextStream:
    agent: str
    message_type: Literal["EXPLANATION", "QA"]
    state_patch: dict[str, Any]
    items: AsyncIterator[LlmTextStreamItem]
    ui_actions: list[dict[str, Any]] = field(default_factory=list)


async def _fixed_text_stream(
    text: str,
    *,
    model: str,
) -> AsyncIterator[LlmTextStreamItem]:
    yield LlmTextDelta(text=text)
    yield LlmTextStreamCompleted(
        usage=LlmUsage(
            model=model,
            input_tokens=0,
            output_tokens=0,
            reasoning_tokens=None,
        )
    )


class ExplainerAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(
        self,
        context: AgentContext,
        detail_level: DetailLevel,
        *,
        timeout_seconds: float,
    ) -> AgentResult:
        if not (context.current_page_text or "").strip():
            return AgentResult(
                agent="ExplainerAgent",
                message=Message(
                    message_type="EXPLANATION",
                    content=_EMPTY_PAGE_EXPLANATION,
                ),
                state_patch={"pageStatus": "EXPLAINED"},
                usage=LlmUsage(self._profile.model, 0, 0, None),
            )
        result = await self._llm.complete_json(
            messages=explainer_messages(context, detail_level),
            response_model=AgentOutput,
            profile=self._profile,
            timeout_seconds=timeout_seconds,
            attachments=_material_attachments(context),
        )
        return AgentResult(
            agent="ExplainerAgent",
            message=Message(message_type="EXPLANATION", content=result.output.markdown),
            state_patch={"pageStatus": "EXPLAINED"},
            usage=result.usage,
        )

    def stream(
        self,
        context: AgentContext,
        detail_level: DetailLevel,
        *,
        timeout_seconds: float,
    ) -> AgentTextStream:
        items = (
            _fixed_text_stream(
                _EMPTY_PAGE_EXPLANATION,
                model=self._profile.model,
            )
            if not (context.current_page_text or "").strip()
            else self._llm.complete_text_stream(
                messages=explainer_messages(
                    context,
                    detail_level,
                    structured=False,
                ),
                profile=self._profile,
                timeout_seconds=timeout_seconds,
                attachments=_material_attachments(context),
            )
        )
        return AgentTextStream(
            agent="ExplainerAgent",
            message_type="EXPLANATION",
            state_patch={"pageStatus": "EXPLAINED"},
            items=items,
        )


class QaAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(
        self,
        context: AgentContext,
        mode: QaThreadMode,
        thread_ref: str | None,
        *,
        timeout_seconds: float,
    ) -> AgentResult:
        state_patch = self._thread_patch(mode, thread_ref)
        redirect = detect_page_redirect(context.event_payload.message or "")
        if redirect is not None:
            is_next = redirect == "NEXT"
            return AgentResult(
                agent="QaAgent",
                message=Message(
                    message_type="QA",
                    content=(_NEXT_PAGE_GUIDANCE if is_next else _PREVIOUS_PAGE_GUIDANCE),
                ),
                state_patch=state_patch,
                usage=LlmUsage(self._profile.model, 0, 0, None),
                ui_actions=(
                    [
                        {
                            "type": "BINARY_DECISION",
                            "content": "다음 페이지로 이동할까요?",
                            "yesEvent": "MOVE_NEXT_PAGE",
                            "noEvent": "WAIT",
                        }
                    ]
                    if is_next
                    else []
                ),
            )
        if context.page_attached and not (context.current_page_text or "").strip():
            return AgentResult(
                agent="QaAgent",
                message=Message(
                    message_type="QA",
                    content=(
                        "제공된 강의 자료만으로는 이 질문에 답하기 어렵습니다. "
                        "현재 페이지와 관련된 질문으로 다시 물어봐 주세요."
                    ),
                ),
                state_patch=state_patch,
                usage=LlmUsage(self._profile.model, 0, 0, None),
            )
        result = await self._llm.complete_json(
            messages=qa_messages(context, mode),
            response_model=AgentOutput,
            profile=self._profile,
            timeout_seconds=timeout_seconds,
            attachments=_material_attachments(context),
        )
        return AgentResult(
            agent="QaAgent",
            message=Message(message_type="QA", content=result.output.markdown),
            state_patch=state_patch,
            usage=result.usage,
        )

    def stream(
        self,
        context: AgentContext,
        mode: QaThreadMode,
        thread_ref: str | None,
        *,
        timeout_seconds: float,
    ) -> AgentTextStream:
        state_patch = self._thread_patch(mode, thread_ref)
        redirect = detect_page_redirect(context.event_payload.message or "")
        ui_actions: list[dict[str, Any]] = []
        if redirect is not None:
            is_next = redirect == "NEXT"
            items = _fixed_text_stream(
                _NEXT_PAGE_GUIDANCE if is_next else _PREVIOUS_PAGE_GUIDANCE,
                model=self._profile.model,
            )
            if is_next:
                ui_actions.append(
                    {
                        "type": "BINARY_DECISION",
                        "content": "다음 페이지로 이동할까요?",
                        "yesEvent": "MOVE_NEXT_PAGE",
                        "noEvent": "WAIT",
                    }
                )
        elif context.page_attached and not (context.current_page_text or "").strip():
            items = _fixed_text_stream(
                (
                    "제공된 강의 자료만으로는 이 질문에 답하기 어렵습니다. "
                    "현재 페이지와 관련된 질문으로 다시 물어봐 주세요."
                ),
                model=self._profile.model,
            )
        else:
            items = self._llm.complete_text_stream(
                messages=qa_messages(context, mode, structured=False),
                profile=self._profile,
                timeout_seconds=timeout_seconds,
                attachments=_material_attachments(context),
            )
        return AgentTextStream(
            agent="QaAgent",
            message_type="QA",
            state_patch=state_patch,
            items=items,
            ui_actions=ui_actions,
        )

    @staticmethod
    def _thread_patch(
        mode: QaThreadMode,
        thread_ref: str | None,
    ) -> dict[str, Any]:
        if mode is QaThreadMode.START_NEW:
            return {"qaThread": {"mode": mode.value}}
        if thread_ref is None:
            raise ValueError("FOLLOW_UP requires threadRef")
        return {"qaThread": {"mode": mode.value, "threadRef": thread_ref}}


class QuizAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(
        self,
        context: AgentContext,
        quiz_type: QuizType,
        *,
        timeout_seconds: float,
    ) -> AgentResult:
        completion = await self._llm.complete_json(
            messages=quiz_messages(context, quiz_type),
            response_model=QuizGeneration,
            profile=self._profile,
            timeout_seconds=timeout_seconds,
            attachments=_material_attachments(context),
        )
        quiz = completion.output
        quiz_context = context.quiz_context
        if quiz_context is None:
            expected_start_page = context.session.current_page
            expected_end_page = context.session.current_page
        else:
            expected_start_page = quiz_context.coverage.start_page
            expected_end_page = quiz_context.coverage.end_page
        if (
            quiz.quiz_type is not quiz_type
            or quiz.coverage.start_page != expected_start_page
            or quiz.coverage.end_page != expected_end_page
        ):
            raise LlmBridgeError(
                category=ErrorCategory.SCHEMA,
                retryable=False,
            )
        return AgentResult(
            agent="QuizAgent",
            message=None,
            state_patch={},
            usage=completion.usage,
            quiz=quiz,
        )


class RepairAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(
        self,
        context: AgentContext,
        *,
        deadline: TurnDeadline,
    ) -> AgentResult:
        usages: list[LlmUsage] = []
        for attempt in range(2):
            try:
                completion = await self._llm.complete_json(
                    messages=repair_messages(context, retry=attempt == 1),
                    response_model=RepairOutput,
                    profile=self._profile,
                    timeout_seconds=deadline.remaining_seconds(),
                )
            except LlmBridgeError as error:
                usages.append(error.usage or unknown_llm_usage(self._profile.model))
                if error.category is ErrorCategory.SCHEMA and attempt == 0:
                    logger.warning(
                        "repair output validation failed",
                        extra={"errorCode": "SCHEMA_INVALID", "attempt": attempt + 1},
                    )
                    continue
                raise

            usages.append(completion.usage)
            return AgentResult(
                agent="RepairAgent",
                message=Message(
                    message_type="REPAIR",
                    content=completion.output.markdown,
                ),
                state_patch={
                    "pageStatus": "REPAIR_COMPLETED",
                    "pendingDiagnosis": None,
                },
                usage=_combined_usage(usages, self._profile.model),
            )
        raise AssertionError("unreachable")


class NoteAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(
        self,
        context: AgentContext,
        note_instruction: str,
        *,
        timeout_seconds: float,
    ) -> AgentResult:
        usages: list[LlmUsage] = []
        retry_reason: str | None = None
        for attempt in range(2):
            try:
                completion = await self._llm.complete_json(
                    messages=note_messages(
                        context,
                        note_instruction,
                        retry_reason=retry_reason,
                    ),
                    response_model=NoteDraft,
                    profile=self._profile,
                    timeout_seconds=timeout_seconds,
                )
            except LlmBridgeError as error:
                usages.append(error.usage or unknown_llm_usage(self._profile.model))
                if error.category is ErrorCategory.SCHEMA and attempt == 0:
                    retry_reason = "SCHEMA_INVALID"
                    logger.warning(
                        "note output validation failed",
                        extra={"errorCode": retry_reason},
                    )
                    continue
                raise

            usages.append(completion.usage)
            violation = _note_output_violation(completion.output)
            if violation is None:
                draft = completion.output.model_copy(
                    update={
                        "title": completion.output.title.strip(),
                        "content": completion.output.content.strip(),
                    }
                )
                return AgentResult(
                    agent="NoteAgent",
                    message=Message(
                        message_type="SYSTEM",
                        content="노트 초안을 만들었어요. 내용을 확인하고 저장해 주세요.",
                    ),
                    state_patch={},
                    usage=_combined_usage(usages, self._profile.model),
                    note_draft=draft,
                )

            retry_reason = violation
            logger.warning(
                "note output validation failed",
                extra={
                    "errorCode": violation,
                    "titleChars": len(completion.output.title),
                    "contentChars": len(completion.output.content),
                },
            )

        raise LlmBridgeError(
            category=ErrorCategory.SCHEMA,
            retryable=False,
            usage=_combined_usage(usages, self._profile.model),
        )


def _note_output_violation(draft: NoteDraft) -> str | None:
    title = draft.title.strip()
    content = draft.content.strip()
    if not title:
        return "EMPTY_TITLE"
    if len(title) > 60:
        return "TITLE_TOO_LONG"
    if not content:
        return "EMPTY_CONTENT"
    combined = f"{title}\n{content}".casefold()
    if any(field_name in combined for field_name in _FORBIDDEN_NOTE_FIELDS):
        return "INTERNAL_FIELD_EXPOSED"
    return None


def _combined_usage(usages: list[LlmUsage], default_model: str) -> LlmUsage:
    return combine_llm_usages(usages, default_model=default_model)
