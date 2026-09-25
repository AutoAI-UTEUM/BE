"""Select an LLM-only question schema without changing the quiz wire contract."""

from copy import deepcopy
from functools import cache
from typing import Literal

from pydantic import create_model

from edupilot_ai.models.quiz import (
    EssayQuestion,
    McqQuestion,
    OxQuestion,
    QuizGeneration,
    QuizType,
    ShortQuestion,
)

_QUESTION_MODELS = {
    QuizType.MCQ: (Literal[QuizType.MCQ], list[McqQuestion]),
    QuizType.OX: (Literal[QuizType.OX], list[OxQuestion]),
    QuizType.SHORT: (Literal[QuizType.SHORT], list[ShortQuestion]),
    QuizType.ESSAY: (Literal[QuizType.ESSAY], list[EssayQuestion]),
}


@cache
def quiz_output_model(quiz_type: QuizType) -> type[QuizGeneration]:
    """Inherit every normalizer/validator, replacing only the union with one type."""
    type_literal, questions_type = _QUESTION_MODELS[quiz_type]
    return create_model(
        f"{quiz_type.value.title()}QuizOutput",
        __base__=QuizGeneration,
        quiz_type=(type_literal, ...),
        questions=(questions_type, deepcopy(QuizGeneration.model_fields["questions"])),
    )
