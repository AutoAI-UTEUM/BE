"""Strict structured-output schemas for the turn pipeline."""

from enum import StrEnum
from typing import Any, Literal

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel


class ToolName(StrEnum):
    EXPLAIN_PAGE = "EXPLAIN_PAGE"
    ANSWER_QUESTION = "ANSWER_QUESTION"
    GENERATE_QUIZ_MCQ = "GENERATE_QUIZ_MCQ"
    GENERATE_QUIZ_OX = "GENERATE_QUIZ_OX"
    GENERATE_QUIZ_SHORT = "GENERATE_QUIZ_SHORT"
    GENERATE_QUIZ_ESSAY = "GENERATE_QUIZ_ESSAY"
    REPAIR_MISCONCEPTION = "REPAIR_MISCONCEPTION"
    BUILD_MEMORY_CANDIDATE = "BUILD_MEMORY_CANDIDATE"
    PROMOTE_MEMORY = "PROMOTE_MEMORY"
    PROMPT_BINARY_DECISION = "PROMPT_BINARY_DECISION"
    PROMPT_QUIZ_TYPE_SELECTION = "PROMPT_QUIZ_TYPE_SELECTION"
    GRADE_OPEN_RESPONSE = "GRADE_OPEN_RESPONSE"
    ASSESS_QUIZ_RESULT = "ASSESS_QUIZ_RESULT"
    DIAGNOSE_MISCONCEPTION = "DIAGNOSE_MISCONCEPTION"
    WRITE_NOTE = "WRITE_NOTE"


class PedagogyPolicy(ContractModel):
    mode: str
    reason: str
    allow_direct_answer: bool
    hint_depth: str
    intervention_budget: int = Field(ge=1, le=10)


class PlanAction(ContractModel):
    action_id: str = Field(min_length=1)
    type: Literal["CALL_TOOL"] = "CALL_TOOL"
    tool: ToolName
    args: dict[str, Any]


class TurnPlan(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    turn_goal: str = Field(min_length=1)
    pedagogy_policy: PedagogyPolicy
    actions: list[PlanAction] = Field(min_length=1, max_length=10)
    reason: str
    memory_write: None = None
    propose_note: bool = False
    stop: str | None = None

    @model_validator(mode="after")
    def validate_actions(self) -> TurnPlan:
        ids = [action.action_id for action in self.actions]
        if len(ids) != len(set(ids)):
            raise ValueError("actionId values must be unique")
        return self


class AgentOutput(ContractModel):
    markdown: str = Field(min_length=1)
