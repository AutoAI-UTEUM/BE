"""Turn agents using injected structured-output LLM."""

import json
import logging
import re
from collections.abc import AsyncIterator, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, Literal

from edupilot_ai.core.async_iterators import closing_async_iterator
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
from edupilot_ai.models.quiz import QuizCoverage, QuizGeneration, QuizType
from edupilot_ai.models.quiz_preview import QuizQuestionStreamEvent
from edupilot_ai.models.turn import DetailLevel, EventType, Message, NoteDraft, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext
from edupilot_ai.orchestration.prompt_cache import turn_prompt_cache
from edupilot_ai.orchestration.prompts import (
    explainer_messages,
    note_messages,
    qa_messages,
    quiz_messages,
    repair_messages,
)
from edupilot_ai.orchestration.quiz_output import quiz_output_model
from edupilot_ai.orchestration.quiz_stream import QuizStreamParser, invalid_quiz_stream
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
    usage: LlmUsage | None
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
    def __init__(
        self,
        *,
        llm: LlmBridge,
        profile: AgentLlmProfile,
        page_context_only_enabled: bool = False,
        page_context_only_pages: Mapping[str, Sequence[int]] | None = None,
    ) -> None:
        self._llm = llm
        self._profile = profile
        self._page_context_only_enabled = page_context_only_enabled
        self._page_context_only_pages = {
            file_id: frozenset(pages) for file_id, pages in (page_context_only_pages or {}).items()
        }

    def _attachments(self, context: AgentContext) -> tuple[LlmFileAttachment, ...]:
        attachments = _material_attachments(context)
        file_id = context.attached_file_id
        if not attachments:
            reason = "NO_FILE"
        elif not self._page_context_only_enabled:
            reason = "DISABLED"
        elif (
            context.event_type is not EventType.EXPLAIN_CURRENT_PAGE
            or context.plan_source != "DETERMINISTIC"
        ):
            reason = "NOT_DETERMINISTIC_EXPLANATION"
        elif (
            context.page_quiz_decision is None
            or context.page_quiz_decision.page_number != context.session.current_page
        ):
            reason = "NO_MATCHING_PAGE_PLAN"
        elif not (context.current_page_text or "").strip():
            reason = "NO_PAGE_TEXT"
        elif context.session.current_page not in self._page_context_only_pages.get(
            file_id or "", frozenset()
        ):
            reason = "PAGE_NOT_REVIEWED"
        else:
            reason = "REVIEWED_PAGE_PLAN"
            attachments = ()
        logger.info(
            "explainer evidence selected",
            extra={
                "agent": "ExplainerAgent",
                "planSource": context.plan_source,
                "evidenceMode": "PDF_ATTACHED" if attachments else "PAGE_CONTEXT",
                "evidenceSelectionReason": reason,
                "pageContextOnlyEnabled": self._page_context_only_enabled,
                "fileAttached": bool(attachments),
                "pageContextChars": sum(
                    len(text or "")
                    for text in (
                        context.current_page_text,
                        context.previous_page_text,
                        context.next_page_text,
                    )
                ),
            },
        )
        return attachments

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
            attachments=self._attachments(context),
            prompt_cache=turn_prompt_cache(context, "explainer"),
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
                attachments=self._attachments(context),
                prompt_cache=turn_prompt_cache(context, "explainer"),
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
            prompt_cache=turn_prompt_cache(context, "qa"),
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
                prompt_cache=turn_prompt_cache(context, "qa"),
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
            response_model=quiz_output_model(quiz_type),
            profile=self._profile,
            timeout_seconds=timeout_seconds,
            attachments=_material_attachments(context),
            prompt_cache=turn_prompt_cache(context, "quiz"),
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

    async def stream(
        self,
        context: AgentContext,
        quiz_type: QuizType,
        *,
        timeout_seconds: float,
    ) -> AsyncIterator[QuizQuestionStreamEvent | AgentResult]:
        """Opt-in private JSON stream; no raw text or private fields in previews."""
        coverage = (
            QuizCoverage(
                start_page=context.quiz_context.coverage.start_page,
                end_page=context.quiz_context.coverage.end_page,
            )
            if context.quiz_context is not None
            else QuizCoverage(
                start_page=context.session.current_page, end_page=context.session.current_page
            )
        )
        parser = QuizStreamParser(quiz_type, coverage)
        messages = list(quiz_messages(context, quiz_type))
        messages[0] = {
            "role": "system",
            "content": str(messages[0]["content"])
            + " 마크다운 코드펜스 없이 아래 스키마의 JSON 객체 하나만 출력하라. "
            "schemaVersion, generationId, quizType, coverage, title, questionCount를 먼저 "
            "작성하고 questions를 마지막 필드로 작성하라. 문항은 배열 순서대로 "
            "완성하라. 정답·해설·루브릭은 기존 스키마대로 반드시 포함하라. "
            + json.dumps(
                quiz_output_model(quiz_type).model_json_schema(by_alias=True),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
        }
        items = self._llm.complete_text_stream(
            messages=messages,
            profile=self._profile,
            timeout_seconds=timeout_seconds,
            attachments=_material_attachments(context),
            prompt_cache=turn_prompt_cache(context, "quiz"),
        )
        usage: LlmUsage | None = None
        async with closing_async_iterator(items):
            async for item in items:
                if usage is not None:
                    raise invalid_quiz_stream()
                if isinstance(item, LlmTextDelta):
                    for preview in parser.feed(item.text):
                        yield preview
                else:
                    usage = item.usage
        if usage is None:
            raise invalid_quiz_stream()
        quiz = parser.finish()
        yield AgentResult(agent="QuizAgent", message=None, state_patch={}, usage=usage, quiz=quiz)


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
        deadline: TurnDeadline,
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
                    timeout_seconds=deadline.remaining_seconds(),
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
