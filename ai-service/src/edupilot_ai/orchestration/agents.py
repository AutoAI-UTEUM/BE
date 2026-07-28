"""Explainer and QA agents using injected structured-output LLM."""

from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any, Literal

from edupilot_ai.llm.bridge import (
    LlmBridge,
    LlmTextDelta,
    LlmTextStreamCompleted,
    LlmTextStreamItem,
    LlmUsage,
)
from edupilot_ai.models.plan import AgentOutput
from edupilot_ai.models.turn import DetailLevel, Message, QaThreadMode
from edupilot_ai.orchestration.context import AgentContext
from edupilot_ai.orchestration.prompts import explainer_messages, qa_messages
from edupilot_ai.settings import AgentLlmProfile


@dataclass(frozen=True, slots=True)
class AgentResult:
    agent: str
    message: Message
    state_patch: dict[str, Any]
    usage: LlmUsage


@dataclass(frozen=True, slots=True)
class AgentTextStream:
    agent: str
    message_type: Literal["EXPLANATION", "QA"]
    state_patch: dict[str, Any]
    items: AsyncIterator[LlmTextStreamItem]


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
        return AgentTextStream(
            agent="ExplainerAgent",
            message_type="EXPLANATION",
            state_patch={"pageStatus": "EXPLAINED"},
            items=self._llm.complete_text_stream(
                messages=explainer_messages(
                    context,
                    detail_level,
                    structured=False,
                ),
                profile=self._profile,
                timeout_seconds=timeout_seconds,
            ),
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
        if not context.current_page_text.strip():
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
        if not context.current_page_text.strip():
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
