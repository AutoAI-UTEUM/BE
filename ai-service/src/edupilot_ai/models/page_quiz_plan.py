"""Material-level decisions, distinct from a learner's runtime quiz consent."""

from pydantic import Field, StrictBool, field_validator

from edupilot_ai.models.base import ContractModel


class PageQuizDecision(ContractModel):
    page_number: int = Field(ge=1)
    suggest_quiz: StrictBool
    reason: str = Field(min_length=1, max_length=240)

    @field_validator("reason")
    @classmethod
    def require_reason(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("page quiz reason must not be blank")
        return value.strip()
