"""Turn agents using injected structured-output LLM."""

import re
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any, Literal

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridge,
    LlmBridgeError,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
)
from edupilot_ai.models.learning_support import RepairOutput
from edupilot_ai.models.plan import AgentOutput
from edupilot_ai.models.quiz import QuizGeneration, QuizType
from edupilot_ai.models.turn import DetailLevel, Message, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext
from edupilot_ai.orchestration.prompts import (
    explainer_messages,
    qa_messages,
    quiz_messages,
    repair_messages,
)
from edupilot_ai.settings import AgentLlmProfile

_NEXT_PAGE_EXPLAIN = re.compile(
    r"(다음|뒤|뒷)\s*(페이지|장|쪽).{0,12}(설명|알려|보여|가르쳐|넘어가)"
)
_PREV_PAGE_EXPLAIN = re.compile(r"(이전|앞)\s*(페이지|장|쪽).{0,12}(설명|알려|보여|가르쳐)")
_NEXT_PAGE_GUIDANCE = (
    "다음 페이지 내용은 페이지를 이동한 뒤에 설명드릴게요. 아래에서 이동을 선택해 주세요."
)
_PREVIOUS_PAGE_GUIDANCE = "이전 페이지 내용은 해당 페이지로 이동하시면 다시 설명드릴 수 있어요."
_EMPTY_PAGE_EXPLANATION = (
    "이 페이지에는 설명할 텍스트 내용이 없어요. 이미지나 도형 중심 페이지라면 "
    "다음 페이지로 이동해 학습을 이어가 주세요."
)


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


@dataclass(frozen=True, slots=True)
class AgentResult:
    agent: str
    message: Message | None
    state_patch: dict[str, Any]
    usage: LlmUsage
    quiz: QuizGeneration | None = None
    memory_candidates: list[dict[str, Any]] = field(default_factory=list)
    ui_actions: list[dict[str, Any]] = field(default_factory=list)


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
        )
        quiz = completion.output
        available_pages = {context.session.current_page}
        if context.previous_page_text is not None and context.session.current_page > 1:
            available_pages.add(context.session.current_page - 1)
        if context.next_page_text is not None:
            available_pages.add(context.session.current_page + 1)
        covered_pages = set(range(quiz.coverage.start_page, quiz.coverage.end_page + 1))
        if quiz.quiz_type is not quiz_type or not covered_pages.issubset(available_pages):
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
        timeout_seconds: float,
    ) -> AgentResult:
        completion = await self._llm.complete_json(
            messages=repair_messages(context),
            response_model=RepairOutput,
            profile=self._profile,
            timeout_seconds=timeout_seconds,
        )
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
            usage=completion.usage,
        )
