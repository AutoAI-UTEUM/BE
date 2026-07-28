"""Draft #30 schemas for generated quizzes.

Public learner fields are ``generationId``, ``quizType``, ``coverage``, ``title``,
``questionCount``, and each question's ``questionId``, ``questionText``, ``points``,
plus MCQ ``choices``. Answer keys, explanations, reference/model answers,
grading criteria, and rubrics are private. Spring owns the public/private DTO split
and persistence boundary.
"""

from enum import StrEnum
from math import isclose
from typing import Annotated, Literal

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel


class QuizType(StrEnum):
    MCQ = "MCQ"
    OX = "OX"
    SHORT = "SHORT"
    ESSAY = "ESSAY"


class QuizCoverage(ContractModel):
    start_page: int = Field(gt=0)
    end_page: int = Field(gt=0)

    @model_validator(mode="after")
    def validate_order(self) -> QuizCoverage:
        if self.end_page < self.start_page:
            raise ValueError("coverage endPage must not precede startPage")
        return self


class QuizChoice(ContractModel):
    """Public MCQ choice."""

    choice_id: str = Field(min_length=1)
    text: str = Field(min_length=1)


class RubricCriterion(ContractModel):
    """Private grading rubric retained only for Spring persistence."""

    criterion: str = Field(min_length=1)
    weight: float = Field(gt=0, le=1)


class QuestionBase(ContractModel):
    """Fields safe for the learner-facing question DTO."""

    question_id: str = Field(min_length=1)
    question_text: str = Field(min_length=1)
    points: float = Field(gt=0)


class McqQuestion(QuestionBase):
    """MCQ choices are public; answerChoiceId and explanation are private."""

    choices: Annotated[list[QuizChoice], Field(min_length=2)]
    answer_choice_id: str = Field(min_length=1)
    explanation: str = Field(min_length=1)

    @model_validator(mode="after")
    def validate_answer(self) -> McqQuestion:
        choice_ids = [choice.choice_id for choice in self.choices]
        if len(choice_ids) != len(set(choice_ids)):
            raise ValueError("choiceId values must be unique")
        if self.answer_choice_id not in choice_ids:
            raise ValueError("answerChoiceId must reference a choice")
        return self


class OxQuestion(QuestionBase):
    """answerValue and explanation are private."""

    answer_value: bool
    explanation: str = Field(min_length=1)


class ShortQuestion(QuestionBase):
    """referenceAnswer and gradingCriteria are private."""

    reference_answer: str = Field(min_length=1)
    grading_criteria: Annotated[list[str], Field(min_length=1)]


class EssayQuestion(QuestionBase):
    """modelAnswer and rubric are private."""

    model_answer: str = Field(min_length=1)
    rubric: Annotated[list[RubricCriterion], Field(min_length=1)]

    @model_validator(mode="after")
    def validate_rubric_weight(self) -> EssayQuestion:
        if not isclose(
            sum(item.weight for item in self.rubric),
            1.0,
            rel_tol=0,
            abs_tol=1e-6,
        ):
            raise ValueError("rubric weights must sum to 1")
        return self


QuizQuestion = McqQuestion | OxQuestion | ShortQuestion | EssayQuestion


class QuizGeneration(ContractModel):
    """Internal quiz artifact containing both public and private fields."""

    schema_version: Literal["1.0"] = "1.0"
    generation_id: str = Field(min_length=1)
    quiz_type: QuizType
    coverage: QuizCoverage
    title: str = Field(min_length=1)
    question_count: int = Field(ge=5, le=10)
    questions: Annotated[list[QuizQuestion], Field(min_length=5, max_length=10)]

    @model_validator(mode="after")
    def validate_questions(self) -> QuizGeneration:
        if self.question_count != len(self.questions):
            raise ValueError("questionCount must match questions length")
        expected_type = {
            QuizType.MCQ: McqQuestion,
            QuizType.OX: OxQuestion,
            QuizType.SHORT: ShortQuestion,
            QuizType.ESSAY: EssayQuestion,
        }[self.quiz_type]
        if any(not isinstance(question, expected_type) for question in self.questions):
            raise ValueError("question schema must match quizType")
        ids = [question.question_id for question in self.questions]
        if len(ids) != len(set(ids)):
            raise ValueError("questionId values must be unique")
        return self
