"""Structured Plan generation with one schema regeneration."""

import logging
from dataclasses import dataclass
from time import perf_counter

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridge,
    LlmBridgeError,
    LlmFileAttachment,
    LlmUsage,
)
from edupilot_ai.models.plan import TurnPlan
from edupilot_ai.models.turn import EventType
from edupilot_ai.orchestration.context import AgentContext, PlanContext
from edupilot_ai.orchestration.prompt_cache import turn_prompt_cache
from edupilot_ai.orchestration.prompts import plan_messages
from edupilot_ai.orchestration.timing import TurnDeadline
from edupilot_ai.settings import AgentLlmProfile
from edupilot_ai.usage import combine_llm_usages, unknown_llm_usage

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class PlanResult:
    plan: TurnPlan
    usage: LlmUsage
    attempts: int = 1


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
        attachments = (
            (LlmFileAttachment(file_id=context.attached_file_id),)
            if context.event_type is EventType.EXPLAIN_CURRENT_PAGE
            and context.attached_file_id is not None
            else ()
        )
        usages: list[LlmUsage] = []
        for attempt in range(2):
            started_at = perf_counter()
            fields = {
                "agent": "Orchestrator",
                "turnId": context.turn_id,
                "eventType": context.event_type.value,
                "attempt": attempt + 1,
            }
            try:
                completion = await self._llm.complete_json(
                    messages=plan_messages(plan_context, retry=attempt == 1),
                    response_model=TurnPlan,
                    profile=self._profile,
                    timeout_seconds=deadline.remaining_seconds(),
                    attachments=attachments,
                    prompt_cache=turn_prompt_cache(context, "planner"),
                )
                logger.info(
                    "planner attempt completed",
                    extra={
                        **fields,
                        "status": "SUCCESS",
                        "durationMs": round((perf_counter() - started_at) * 1000, 3),
                    },
                )
                usages.append(completion.usage)
                return PlanResult(
                    plan=completion.output,
                    usage=combine_llm_usages(usages, default_model=self._profile.model),
                    attempts=attempt + 1,
                )
            except LlmBridgeError as error:
                logger.warning(
                    "planner attempt failed",
                    extra={
                        **fields,
                        "status": (
                            "RETRYING"
                            if error.category is ErrorCategory.SCHEMA and attempt == 0
                            else "FAILED"
                        ),
                        "errorCode": error.category.value,
                        "durationMs": round((perf_counter() - started_at) * 1000, 3),
                    },
                )
                usages.append(error.usage or unknown_llm_usage(self._profile.model))
                if error.category is not ErrorCategory.SCHEMA or attempt == 1:
                    raise
        raise AssertionError("unreachable")
