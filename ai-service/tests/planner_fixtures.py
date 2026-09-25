"""Explicit migration of legacy execution-Plan fixtures to LLM-only decisions."""

from edupilot_ai.models.plan import ToolName, TurnPlan
from edupilot_ai.orchestration.planner_output import PlannerOutput


def planner_output(plan: TurnPlan) -> PlannerOutput:
    data = plan.model_dump(mode="json", by_alias=True)
    for key in ("schemaVersion", "memoryWrite"):
        del data[key]
    for action in data["actions"]:
        del action["actionId"]
        del action["type"]
        tool = ToolName(action["tool"])
        if tool is ToolName.ANSWER_QUESTION:
            action["args"].pop("threadRef", None)
        elif tool in {
            ToolName.EXPLAIN_PAGE,
            ToolName.PROMPT_BINARY_DECISION,
            ToolName.GENERATE_QUIZ_MCQ,
            ToolName.GENERATE_QUIZ_OX,
            ToolName.GENERATE_QUIZ_SHORT,
            ToolName.GENERATE_QUIZ_ESSAY,
            ToolName.REPAIR_MISCONCEPTION,
        }:
            action["args"] = {}
    return PlannerOutput.model_validate(data)
