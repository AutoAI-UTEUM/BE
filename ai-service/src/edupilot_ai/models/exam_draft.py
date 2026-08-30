"""Strict contracts for stateless exam question draft generation."""

from typing import Annotated, Literal, Self

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel, Usage
from edupilot_ai.models.quiz import (
    EssayQuestion,
    McqQuestion,
    OxQuestion,
    QuizType,
    ShortQuestion,
)


class ExamPageContext(ContractModel):
    page_number: int = Field(gt=0)
    text: str = Field(min_length=1)


class ExamQuestionPlanItem(ContractModel):
    question_type: QuizType
    count: int = Field(ge=1)


class DraftMcqQuestion(McqQuestion):
    question_type: Literal["MCQ"] = "MCQ"
    source_page_number: int | None = Field(default=None, gt=0)


class DraftOxQuestion(OxQuestion):
    question_type: Literal["OX"] = "OX"
    source_page_number: int | None = Field(default=None, gt=0)


class DraftShortQuestion(ShortQuestion):
    question_type: Literal["SHORT"] = "SHORT"
    source_page_number: int | None = Field(default=None, gt=0)


class DraftEssayQuestion(EssayQuestion):
    question_type: Literal["ESSAY"] = "ESSAY"
    source_page_number: int | None = Field(default=None, gt=0)


DraftQuestion = Annotated[
    DraftMcqQuestion | DraftOxQuestion | DraftShortQuestion | DraftEssayQuestion,
    Field(discriminator="question_type"),
]


class ExamDraftRequest(ContractModel):
    schema_version: Literal["1.0"]
    exam_id: int = Field(strict=True, gt=0)
    page_contexts: list[ExamPageContext] = Field(min_length=1, max_length=30)
    question_plan: list[ExamQuestionPlanItem] = Field(min_length=1, max_length=4)

    @model_validator(mode="after")
    def validate_request(self) -> Self:
        page_numbers = [context.page_number for context in self.page_contexts]
        if len(page_numbers) != len(set(page_numbers)):
            raise ValueError("pageNumber values must be unique")

        question_types = [item.question_type for item in self.question_plan]
        if len(question_types) != len(set(question_types)):
            raise ValueError("questionType values must be unique")

        total = sum(item.count for item in self.question_plan)
        if not 1 <= total <= 20:
            raise ValueError("questionPlan total count must be between 1 and 20")
        return self


class ExamDraftOutput(ContractModel):
    questions: list[DraftQuestion] = Field(min_length=1, max_length=20)


class ExamDraftResponse(ExamDraftOutput):
    schema_version: Literal["1.0"] = "1.0"
    exam_id: int = Field(strict=True, gt=0)
    usage: Usage | None = None
