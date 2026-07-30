"""Structured Plan generation with one schema regeneration."""

from dataclasses import dataclass

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridge, LlmBridgeError, LlmUsage
from edupilot_ai.models.plan import TurnPlan
from edupilot_ai.orchestration.context import AgentContext, PlanContext
from edupilot_ai.orchestration.prompts import plan_messages
from edupilot_ai.orchestration.timing import TurnDeadline
from edupilot_ai.settings import AgentLlmProfile


@dataclass(frozen=True, slots=True)
class PlanResult:
    plan: TurnPlan
    usage: LlmUsage


class Orchestrator:
    def __init__(self, *, llm: LlmBridge, profile: AgentLlmProfile) -> None:
        self._llm = llm
        self._profile = profile

    async def create_plan(
        self,
        context: AgentContext,
        deadline: TurnDeadline,
    ) -> PlanResult:
        plan_context = PlanContext.from_agent_context(context)
        for attempt in range(2):
            try:
                completion = await self._llm.complete_json(
                    messages=plan_messages(plan_context, retry=attempt == 1),
                    response_model=TurnPlan,
                    profile=self._profile,
                    timeout_seconds=deadline.remaining_seconds(),
                )
                return PlanResult(plan=completion.output, usage=completion.usage)
            except LlmBridgeError as error:
                if error.category is not ErrorCategory.SCHEMA or attempt == 1:
                    raise
        raise AssertionError("unreachable")
