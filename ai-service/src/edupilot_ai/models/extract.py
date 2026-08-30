"""Versioned DTOs for POST /internal/ai/extract."""

from typing import Literal

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel, Usage


class ExtractedPage(ContractModel):
    """Text extracted from one 1-based PDF page."""

    page_number: int = Field(ge=1)
    text: str


class ExtractWarning(ContractModel):
    """Non-fatal post-extraction warning safe for Spring persistence."""

    type: Literal["FILE_UPLOAD_FAILED"]
    message: str = Field(min_length=1)


class ExtractResponse(ContractModel):
    """Complete deterministic extraction result."""

    schema_version: Literal["1.0"] = "1.0"
    page_count: int = Field(ge=1)
    pages: list[ExtractedPage]
    xai_file_id: str | None = None
    warnings: list[ExtractWarning] = Field(default_factory=list)
    usage: Usage | None = None

    @model_validator(mode="after")
    def validate_pages(self) -> ExtractResponse:
        """Keep pageCount, array length, and 1-based ordering aligned."""
        expected_numbers = list(range(1, self.page_count + 1))
        actual_numbers = [page.page_number for page in self.pages]
        if len(self.pages) != self.page_count or actual_numbers != expected_numbers:
            raise ValueError("pages must contain every page in 1-based order")
        return self
