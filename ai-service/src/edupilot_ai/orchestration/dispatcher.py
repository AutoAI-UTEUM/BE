"""Sequential tool execution and statePatch merging."""

from dataclasses import dataclass, field
from typing import Any

from edupilot_ai.llm.bridge import LlmBridgeError, LlmUsage
from edupilot_ai.models.plan import PlanAction, ToolName, TurnPlan
from edupilot_ai.models.turn import (
    ActionExecuted,
    Adjustment,
    DetailLevel,
    Message,
    QaThreadMode,
)
from edupilot_ai.orchestration.agents import AgentResult, ExplainerAgent, QaAgent
from edupilot_ai.orchestration.context import AgentContext
from edupilot_ai.orchestration.policy import PolicyViolation

_PAGE_STATUS_VALUES = {
    "EXPLAINING",
    "EXPLAINED",
    "QUIZ_READY",
    "DIAGNOSIS_PENDING",
    "REPAIR_COMPLETED",
}
_ALLOWED_PATCH_KEYS = {"pageStatus", "activeQuizId", "pendingDiagnosis", "qaThread"}


def merge_state_patch(current: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
    if not set(incoming).issubset(_ALLOWED_PATCH_KEYS):
        raise PolicyViolation("statePatch key is not allowed")
    if "pageStatus" in incoming and incoming["pageStatus"] not in _PAGE_STATUS_VALUES:
        raise PolicyViolation("pageStatus value is not allowed")
    if "qaThread" in incoming:
        thread = incoming["qaThread"]
        if not isinstance(thread, dict):
            raise PolicyViolation("qaThread patch is invalid")
        mode = thread.get("mode")
        if mode not in {item.value for item in QaThreadMode}:
            raise PolicyViolation("qaThread mode is invalid")
        if mode == QaThreadMode.START_NEW:
            if set(thread) != {"mode"}:
                raise PolicyViolation("START_NEW qaThread patch is invalid")
        elif set(thread) != {"mode", "threadRef"}:
            raise PolicyViolation("FOLLOW_UP qaThread patch is invalid")
        elif not isinstance(thread["threadRef"], str) or not thread["threadRef"]:
            raise PolicyViolation("qaThread reference is invalid")
    merged = dict(current)
    for key, value in incoming.items():
        if key in merged and merged[key] != value:
            raise PolicyViolation("conflicting statePatch values")
        merged[key] = value
    return merged


@dataclass(slots=True)
class DispatchResult:
    actions: list[ActionExecuted] = field(default_factory=list)
    messages: list[Message] = field(default_factory=list)
    state_patch: dict[str, Any] = field(default_factory=dict)
    ui_actions: list[dict[str, Any]] = field(default_factory=list)
    usages: list[LlmUsage] = field(default_factory=list)
    failure: LlmBridgeError | PolicyViolation | None = None


class ToolDispatcher:
    def __init__(self, *, explainer: ExplainerAgent, qa: QaAgent, model: str) -> None:
        self._explainer = explainer
        self._qa = qa
        self._model = model

    async def dispatch(
        self,
        plan: TurnPlan,
        context: AgentContext,
        adjustments: list[Adjustment] | None = None,
    ) -> DispatchResult:
        result = DispatchResult()
        verified_adjustments = adjustments or []
        for action in plan.actions:
            action_adjustments = [
                item
                for item in verified_adjustments
                if item.belongs_to(action.action_id)
            ]
            try:
                outcome = await self._execute(action, context)
                result.state_patch = merge_state_patch(result.state_patch, outcome.state_patch)
                result.actions.append(
                    ActionExecuted(
                        action_id=action.action_id,
                        agent=outcome.agent,
                        status="SUCCESS",
                        adjustments=action_adjustments,
                    )
                )
                result.messages.append(outcome.message)
                result.usages.append(outcome.usage)
            except (LlmBridgeError, PolicyViolation) as error:
                result.actions.append(
                    ActionExecuted(
                        action_id=action.action_id,
                        agent=action.tool.value,
                        status="FAILED",
                        adjustments=action_adjustments,
                    )
                )
                result.failure = error
                break
        return result

    async def _execute(self, action: PlanAction, context: AgentContext) -> AgentResult:
        if action.tool is ToolName.EXPLAIN_PAGE:
            return await self._explainer.run(
                context,
                DetailLevel(str(action.args["detailLevel"])),
            )
        if action.tool is ToolName.ANSWER_QUESTION:
            mode = QaThreadMode(str(action.args["qaThreadMode"]))
            thread_ref = None if mode is QaThreadMode.START_NEW else context.qa_thread_ref()
            if mode is QaThreadMode.FOLLOW_UP and thread_ref is None:
                raise PolicyViolation("FOLLOW_UP requires qaThreadDigest threadRef")
            return await self._qa.run(context, mode, thread_ref)
        if action.tool.value.startswith("GENERATE_QUIZ_"):
            return self._stub("QuizAgent", "퀴즈 생성 기능은 준비 중입니다. (이슈 #31)")
        if action.tool is ToolName.REPAIR_MISCONCEPTION:
            return self._stub("RepairAgent", "오개념 교정 기능은 준비 중입니다. (이슈 #38)")
        raise PolicyViolation("tool is not implemented in issue #23")

    def _stub(self, agent: str, content: str) -> AgentResult:
        return AgentResult(
            agent=agent,
            message=Message(message_type="SYSTEM", content=content),
            state_patch={},
            usage=LlmUsage(self._model, 0, 0, None),
        )
