"""LLM-only Plan representation; expand fixed fields before normal Policy checks."""

from typing import Any

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.plan import PedagogyPolicy, PlanAction, ToolName, TurnPlan
from edupilot_ai.orchestration.context import AgentContext

_FIXED_ARGS_TOOLS = {
    ToolName.EXPLAIN_PAGE,
    ToolName.PROMPT_BINARY_DECISION,
    ToolName.GENERATE_QUIZ_MCQ,
    ToolName.GENERATE_QUIZ_OX,
    ToolName.GENERATE_QUIZ_SHORT,
    ToolName.GENERATE_QUIZ_ESSAY,
    ToolName.REPAIR_MISCONCEPTION,
}


class PlannerAction(ContractModel):
    tool: ToolName
    args: dict[str, Any]

    @model_validator(mode="after")
    def validate_fixed_args_are_omitted(self) -> PlannerAction:
        if self.tool in _FIXED_ARGS_TOOLS and self.args:
            raise ValueError("fixed tool args must be empty in PlannerOutput")
        if self.tool is ToolName.ANSWER_QUESTION and set(self.args) != {"qaThreadMode"}:
            raise ValueError("ANSWER_QUESTION requires only qaThreadMode in PlannerOutput")
        return self


class PlannerOutput(ContractModel):
    """Keep every learning decision; omit only deterministically recoverable echoes."""

    turn_goal: str = Field(min_length=1)
    pedagogy_policy: PedagogyPolicy
    actions: list[PlannerAction] = Field(min_length=1, max_length=10)
    reason: str
    propose_note: bool = False
    stop: str | None = None


def expand_planner_output(output: PlannerOutput, context: AgentContext) -> TurnPlan:
    """Restore the execution Plan, without approving actions or raising their budget."""
    actions: list[PlanAction] = []
    for index, action in enumerate(output.actions, start=1):
        args = dict(action.args)
        if action.tool is ToolName.EXPLAIN_PAGE:
            args = {
                "page": context.session.current_page,
                "detailLevel": context.event_payload.detail_level,
            }
        elif action.tool is ToolName.PROMPT_BINARY_DECISION:
            args = {
                "contentMarkdown": "퀴즈를 진행할까요?",
                "decisionType": "QUIZ_DECISION",
            }
        elif action.tool is ToolName.ANSWER_QUESTION:
            mode = args["qaThreadMode"]
            # Preserve the existing Policy alias handling; never invent a thread.
            follow_up = isinstance(mode, str) and mode.upper() in {
                "FOLLOW_UP",
                "FOLLOWUP",
                "FOLLOW-UP",
            }
            args["threadRef"] = context.qa_thread_ref() if follow_up else None
        elif action.tool in {
            ToolName.GENERATE_QUIZ_MCQ,
            ToolName.GENERATE_QUIZ_OX,
            ToolName.GENERATE_QUIZ_SHORT,
            ToolName.GENERATE_QUIZ_ESSAY,
        }:
            args = {"quizType": context.event_payload.quiz_type}
        elif action.tool is ToolName.REPAIR_MISCONCEPTION:
            args = {"diagnosisId": context.event_payload.diagnosis_id}
        actions.append(PlanAction(action_id=f"action-{index}", tool=action.tool, args=args))

    return TurnPlan(
        turn_goal=output.turn_goal,
        pedagogy_policy=output.pedagogy_policy.model_copy(deep=True),
        actions=actions,
        reason=output.reason,
        propose_note=output.propose_note,
        stop=output.stop,
    )
