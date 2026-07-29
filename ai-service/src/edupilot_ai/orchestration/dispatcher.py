"""Sequential tool execution and statePatch merging."""

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import (
    LlmBridgeError,
    LlmTextDelta,
    LlmUsage,
)
from edupilot_ai.models.plan import PlanAction, ToolName, TurnPlan
from edupilot_ai.models.quiz import QuizGeneration, QuizType
from edupilot_ai.models.turn import (
    ActionExecuted,
    Adjustment,
    DetailLevel,
    Message,
    QaThreadMode,
)
from edupilot_ai.orchestration.agents import (
    AgentResult,
    AgentTextStream,
    ExplainerAgent,
    QaAgent,
    QuizAgent,
    RepairAgent,
)
from edupilot_ai.orchestration.context import AgentContext
from edupilot_ai.orchestration.policy import PolicyViolation
from edupilot_ai.orchestration.timing import TurnDeadline

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
    quiz: QuizGeneration | None = None
    memory_candidates: list[dict[str, Any]] = field(default_factory=list)
    memory_write: dict[str, Any] | None = None
    failure: LlmBridgeError | PolicyViolation | None = None


@dataclass(frozen=True, slots=True)
class DispatchTextDelta:
    text: str


@dataclass(frozen=True, slots=True)
class DispatchStreamCompleted:
    result: DispatchResult


type DispatchStreamItem = DispatchTextDelta | DispatchStreamCompleted


class ToolDispatcher:
    def __init__(
        self,
        *,
        explainer: ExplainerAgent,
        qa: QaAgent,
        model: str,
        quiz: QuizAgent | None = None,
        repair: RepairAgent | None = None,
    ) -> None:
        self._explainer = explainer
        self._qa = qa
        self._model = model
        self._quiz = quiz
        self._repair = repair

    async def dispatch(
        self,
        plan: TurnPlan,
        context: AgentContext,
        deadline: TurnDeadline,
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
                outcome = await self._execute(action, context, deadline)
                result.state_patch = merge_state_patch(result.state_patch, outcome.state_patch)
                result.actions.append(
                    ActionExecuted(
                        action_id=action.action_id,
                        agent=outcome.agent,
                        status="SUCCESS",
                        adjustments=action_adjustments,
                    )
                )
                if outcome.message is not None:
                    result.messages.append(outcome.message)
                if outcome.quiz is not None:
                    if result.quiz is not None:
                        raise PolicyViolation("multiple quiz results are not allowed")
                    result.quiz = outcome.quiz
                self._record_memory_result(result, action, outcome)
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

    async def dispatch_stream(
        self,
        plan: TurnPlan,
        context: AgentContext,
        adjustments: list[Adjustment],
        deadline: TurnDeadline,
    ) -> AsyncIterator[DispatchStreamItem]:
        result = DispatchResult()
        for action in plan.actions:
            action_adjustments = [
                item for item in adjustments if item.belongs_to(action.action_id)
            ]
            try:
                if action.tool in {ToolName.EXPLAIN_PAGE, ToolName.ANSWER_QUESTION}:
                    stream = self._agent_stream(action, context, deadline)
                    content: list[str] = []
                    usage: LlmUsage | None = None
                    async for item in stream.items:
                        if isinstance(item, LlmTextDelta):
                            if usage is not None:
                                raise LlmBridgeError(
                                    category=ErrorCategory.SCHEMA,
                                    retryable=False,
                                )
                            content.append(item.text)
                            yield DispatchTextDelta(text=item.text)
                        elif usage is None:
                            usage = item.usage
                        else:
                            raise LlmBridgeError(
                                category=ErrorCategory.SCHEMA,
                                retryable=False,
                            )
                    if usage is None or not content:
                        raise LlmBridgeError(
                            category=ErrorCategory.SCHEMA,
                            retryable=False,
                        )
                    outcome = AgentResult(
                        agent=stream.agent,
                        message=Message(
                            message_type=stream.message_type,
                            content="".join(content),
                        ),
                        state_patch=stream.state_patch,
                        usage=usage,
                    )
                else:
                    outcome = await self._execute(action, context, deadline)
                result.state_patch = merge_state_patch(
                    result.state_patch,
                    outcome.state_patch,
                )
                result.actions.append(
                    ActionExecuted(
                        action_id=action.action_id,
                        agent=outcome.agent,
                        status="SUCCESS",
                        adjustments=action_adjustments,
                    )
                )
                if outcome.message is not None:
                    result.messages.append(outcome.message)
                if outcome.quiz is not None:
                    if result.quiz is not None:
                        raise PolicyViolation("multiple quiz results are not allowed")
                    result.quiz = outcome.quiz
                self._record_memory_result(result, action, outcome)
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
        yield DispatchStreamCompleted(result=result)

    async def _execute(
        self,
        action: PlanAction,
        context: AgentContext,
        deadline: TurnDeadline,
    ) -> AgentResult:
        if action.tool is ToolName.EXPLAIN_PAGE:
            return await self._explainer.run(
                context,
                DetailLevel(str(action.args["detailLevel"])),
                timeout_seconds=deadline.remaining_seconds(),
            )
        if action.tool is ToolName.ANSWER_QUESTION:
            mode = QaThreadMode(str(action.args["qaThreadMode"]))
            thread_ref = None if mode is QaThreadMode.START_NEW else context.qa_thread_ref()
            if mode is QaThreadMode.FOLLOW_UP and thread_ref is None:
                raise PolicyViolation("FOLLOW_UP requires qaThreadDigest threadRef")
            return await self._qa.run(
                context,
                mode,
                thread_ref,
                timeout_seconds=deadline.remaining_seconds(),
            )
        if action.tool.value.startswith("GENERATE_QUIZ_"):
            if self._quiz is None:
                raise PolicyViolation("QuizAgent is not configured")
            quiz_type = QuizType(str(action.args["quizType"]))
            return await self._quiz.run(
                context,
                quiz_type,
                timeout_seconds=deadline.remaining_seconds(),
            )
        if action.tool is ToolName.REPAIR_MISCONCEPTION:
            if self._repair is None:
                raise PolicyViolation("RepairAgent is not configured")
            return await self._repair.run(
                context,
                timeout_seconds=deadline.remaining_seconds(),
            )
        if action.tool in {
            ToolName.BUILD_MEMORY_CANDIDATE,
            ToolName.PROMOTE_MEMORY,
        }:
            # memoryWrite is canonical; promotionRequested remains for compatibility.
            candidate = {
                "type": action.args["type"],
                "content": action.args["content"],
                "confidence": action.args["confidence"],
                "evidence": action.args["evidence"],
                "promotionRequested": action.tool is ToolName.PROMOTE_MEMORY,
            }
            return AgentResult(
                agent="LearnerMemoryService",
                message=None,
                state_patch={},
                usage=LlmUsage(self._model, 0, 0, None),
                memory_candidates=[candidate],
            )
        raise PolicyViolation("tool is not implemented in issue #23")

    @staticmethod
    def _record_memory_result(
        result: DispatchResult,
        action: PlanAction,
        outcome: AgentResult,
    ) -> None:
        result.memory_candidates.extend(outcome.memory_candidates)
        if action.tool is not ToolName.PROMOTE_MEMORY:
            return
        if result.memory_write is not None:
            raise PolicyViolation("multiple memory promotions in one turn")
        if len(outcome.memory_candidates) != 1:
            raise PolicyViolation("memory promotion result is invalid")
        candidate = outcome.memory_candidates[0]
        result.memory_write = {
            key: candidate[key]
            for key in ("type", "content", "confidence", "evidence")
        }

    def _agent_stream(
        self,
        action: PlanAction,
        context: AgentContext,
        deadline: TurnDeadline,
    ) -> AgentTextStream:
        if action.tool is ToolName.EXPLAIN_PAGE:
            return self._explainer.stream(
                context,
                DetailLevel(str(action.args["detailLevel"])),
                timeout_seconds=deadline.remaining_seconds(),
            )
        mode = QaThreadMode(str(action.args["qaThreadMode"]))
        thread_ref = None if mode is QaThreadMode.START_NEW else context.qa_thread_ref()
        if mode is QaThreadMode.FOLLOW_UP and thread_ref is None:
            raise PolicyViolation("FOLLOW_UP requires qaThreadDigest threadRef")
        return self._qa.stream(
            context,
            mode,
            thread_ref,
            timeout_seconds=deadline.remaining_seconds(),
        )

    def _stub(self, agent: str, content: str) -> AgentResult:
        return AgentResult(
            agent=agent,
            message=Message(message_type="SYSTEM", content=content),
            state_patch={},
            usage=LlmUsage(self._model, 0, 0, None),
        )
