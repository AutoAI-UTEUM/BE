"""Explainer and QA agents using injected structured-output LLM."""

from dataclasses import dataclass
from typing import Any

from edupilot_ai.llm.bridge import LlmBridge, LlmUsage
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


class ExplainerAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(self, context: AgentContext, detail_level: DetailLevel) -> AgentResult:
        result = await self._llm.complete_json(
            messages=explainer_messages(context, detail_level),
            response_model=AgentOutput,
            profile=self._profile,
        )
        return AgentResult(
            agent="ExplainerAgent",
            message=Message(message_type="EXPLANATION", content=result.output.markdown),
            state_patch={"pageStatus": "EXPLAINED"},
            usage=result.usage,
        )


class QaAgent:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def run(
        self,
        context: AgentContext,
        mode: QaThreadMode,
        thread_ref: str,
    ) -> AgentResult:
        if not context.current_page_text.strip():
            return AgentResult(
                agent="QaAgent",
                message=Message(
                    message_type="QA",
                    content="The supplied page context is insufficient to answer this question.",
                ),
                state_patch={"qaThread": {"mode": mode.value, "threadRef": thread_ref}},
                usage=LlmUsage(self._profile.model, 0, 0, None),
            )
        result = await self._llm.complete_json(
            messages=qa_messages(context, mode),
            response_model=AgentOutput,
            profile=self._profile,
        )
        return AgentResult(
            agent="QaAgent",
            message=Message(message_type="QA", content=result.output.markdown),
            state_patch={"qaThread": {"mode": mode.value, "threadRef": thread_ref}},
            usage=result.usage,
        )
