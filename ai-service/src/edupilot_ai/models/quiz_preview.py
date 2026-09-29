"""Opt-in provisional quiz event; explicitly allowlist learner-visible fields."""

from typing import Literal

from pydantic import Field

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.quiz import QuestionBase, QuizChoice, QuizCoverage, QuizType


class PublicQuizQuestion(QuestionBase):
    choices: list[QuizChoice] | None = Field(default=None, exclude_if=lambda value: value is None)


class QuizQuestionStreamEvent(ContractModel):
    type: Literal["quiz_question"] = "quiz_question"
    generation_id: str
    quiz_type: QuizType
    title: str
    coverage: QuizCoverage
    question_index: int = Field(ge=1, le=5)
    question_count: Literal[5] = 5
    provisional: Literal[True] = True
    question: PublicQuizQuestion
