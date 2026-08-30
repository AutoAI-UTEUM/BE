"""Strict contracts for material outline generation."""

from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from edupilot_ai.models.base import ContractModel, Usage


class OutlinePage(ContractModel):
    page_number: int = Field(ge=1)
    text: str


class OutlineRequest(ContractModel):
    schema_version: Literal["1.0"]
    xai_file_id: str | None = Field(default=None, min_length=1)
    total_pages: int = Field(ge=1)
    pages: list[OutlinePage] = Field(min_length=1)

    @field_validator("xai_file_id")
    @classmethod
    def normalize_xai_file_id(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        if not normalized:
            raise ValueError("xaiFileId must not be blank")
        return normalized

    @model_validator(mode="after")
    def validate_pages(self) -> Self:
        page_numbers = [page.page_number for page in self.pages]
        if len(page_numbers) != len(set(page_numbers)):
            raise ValueError("pageNumber values must be unique")
        if any(page_number > self.total_pages for page_number in page_numbers):
            raise ValueError("pageNumber must be within totalPages")
        return self


class OutlineSection(ContractModel):
    title: str = Field(min_length=1)
    description: str | None = Field(default=None)
    start_page: int = Field(ge=1)
    end_page: int = Field(ge=1)
    keywords: list[str] = Field(max_length=5)


class QuizCheckpointCoverage(ContractModel):
    start_page: int = Field(ge=1)
    end_page: int = Field(ge=1)


class QuizCheckpoint(ContractModel):
    trigger_page: int = Field(ge=1)
    coverage: QuizCheckpointCoverage


class OutlineOutput(ContractModel):
    material_summary: str = Field(min_length=1)
    sections: list[OutlineSection] = Field(min_length=1)
    quiz_checkpoints: list[QuizCheckpoint] = Field(min_length=1, max_length=10)


class OutlineResponse(OutlineOutput):
    schema_version: Literal["1.0"] = "1.0"
    total_pages: int = Field(ge=1)
    usage: Usage | None = None
