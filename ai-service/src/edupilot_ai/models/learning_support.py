"""Draft #36 DTOs for assessment, diagnosis, repair, and memory."""

from typing import Annotated, Any, Literal

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.quiz import QuizType
from edupilot_ai.models.turn import Usage


class SupportPageContext(ContractModel):
    coverage_start_page: int = Field(gt=0)
    coverage_end_page: int = Field(gt=0)
    text: str

    @model_validator(mode="after")
    def validate_coverage(self) -> SupportPageContext:
        if self.coverage_end_page < self.coverage_start_page:
            raise ValueError("coverage page range is invalid")
        return self


class QuizResult(ContractModel):
    quiz_id: int | str
    quiz_type: QuizType
    score: float = Field(ge=0)
    max_score: float = Field(gt=0)
    passed: bool
    items: list[dict[str, Any]]

    @model_validator(mode="after")
    def validate_score(self) -> QuizResult:
        if self.score > self.max_score:
            raise ValueError("score must not exceed maxScore")
        return self


class MemoryCandidate(ContractModel):
    type: Literal["STRENGTH", "WEAKNESS", "MISCONCEPTION", "PREFERENCE"]
    content: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)


class AssessmentRequest(ContractModel):
    schema_version: Literal["1.0"]
    quiz_result: QuizResult
    quiz_items: list[dict[str, Any]]
    student_answers: list[dict[str, Any]]
    page_context: SupportPageContext
    learner_memory_digest: str | None


class AssessmentOutput(ContractModel):
    understanding_summary: str = Field(min_length=1)
    strengths: list[str]
    weaknesses: list[str]
    suspected_misconceptions: list[str]
    recommended_next_direction: str = Field(min_length=1)
    memory_candidates: list[MemoryCandidate]
    evidence: list[str]


class AssessmentResponse(AssessmentOutput):
    schema_version: Literal["1.0"] = "1.0"
    usage: Usage


class WrongItem(ContractModel):
    question_id: str = Field(min_length=1)
    question: str = Field(min_length=1)
    student_answer: str
    model_answer: str = Field(min_length=1)
    feedback: str = Field(min_length=1)


class DiagnosisRequest(ContractModel):
    schema_version: Literal["1.0"]
    quiz_assessment: dict[str, Any]
    quiz_result: QuizResult
    wrong_items: Annotated[list[WrongItem], Field(min_length=1)]
    page_context: SupportPageContext
    learner_memory_digest: str | None


class DiagnosisOutput(ContractModel):
    focus_concepts: Annotated[list[str], Field(min_length=1)]
    suspected_misconceptions: list[str]
    diagnostic_prompt: str = Field(min_length=1)
    evidence: Annotated[list[str], Field(min_length=1)]
    repair_hint: str = Field(min_length=1)


class DiagnosisResponse(DiagnosisOutput):
    schema_version: Literal["1.0"] = "1.0"
    usage: Usage


class RepairOutput(ContractModel):
    markdown: str = Field(min_length=1)
