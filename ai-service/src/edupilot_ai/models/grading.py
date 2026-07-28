"""Confirmed #30 DTOs for POST /internal/ai/grade."""

from math import isclose
from typing import Annotated, Any, Literal

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.quiz import QuizType, RubricCriterion
from edupilot_ai.models.turn import Usage


class GradeItem(ContractModel):
    question_id: str = Field(min_length=1)
    question: str = Field(min_length=1)
    model_answer: str = Field(min_length=1)
    rubric: Annotated[list[RubricCriterion], Field(min_length=1)]
    max_score: float = Field(gt=0)

    @model_validator(mode="after")
    def validate_rubric(self) -> GradeItem:
        if not isclose(
            sum(item.weight for item in self.rubric),
            1.0,
            rel_tol=0,
            abs_tol=1e-6,
        ):
            raise ValueError("rubric weights must sum to 1")
        return self


class StudentAnswer(ContractModel):
    question_id: str = Field(min_length=1)
    answer: str


class GradePageContext(ContractModel):
    coverage_start_page: int = Field(gt=0)
    coverage_end_page: int = Field(gt=0)
    text: str

    @model_validator(mode="after")
    def validate_coverage(self) -> GradePageContext:
        if self.coverage_end_page < self.coverage_start_page:
            raise ValueError("coverage page range is invalid")
        return self


class GradeRequest(ContractModel):
    schema_version: Literal["1.0"]
    quiz_id: int | str
    quiz_type: QuizType
    items: Annotated[list[GradeItem], Field(min_length=1)]
    student_answers: Annotated[list[StudentAnswer], Field(min_length=1)]
    page_context: GradePageContext
    learner_memory_digest: dict[str, Any] | str | None

    @model_validator(mode="after")
    def validate_quiz(self) -> GradeRequest:
        if self.quiz_type not in {QuizType.SHORT, QuizType.ESSAY}:
            raise ValueError("only SHORT and ESSAY are graded by AI")
        if isinstance(self.quiz_id, int):
            if self.quiz_id <= 0:
                raise ValueError("quizId must be positive")
        elif not self.quiz_id.strip():
            raise ValueError("quizId must not be blank")
        return self


class RubricScore(ContractModel):
    criterion: str = Field(min_length=1)
    score_ratio: float = Field(ge=0, le=1)


class GraderItemOutput(ContractModel):
    question_id: str = Field(min_length=1)
    rubric_scores: Annotated[list[RubricScore], Field(min_length=1)]
    score: float = Field(ge=0)
    verdict: Literal["CORRECT", "PARTIAL", "WRONG"]
    feedback: str = Field(min_length=1)


class GraderOutput(ContractModel):
    items: Annotated[list[GraderItemOutput], Field(min_length=1)]


class GradeResultItem(ContractModel):
    question_id: str
    score: float = Field(ge=0)
    max_score: float = Field(gt=0)
    verdict: Literal["CORRECT", "PARTIAL", "WRONG"]
    feedback: str


class GradeResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    quiz_id: int | str
    quiz_type: QuizType
    score: float = Field(ge=0)
    max_score: float = Field(gt=0)
    items: list[GradeResultItem]
    usage: Usage
