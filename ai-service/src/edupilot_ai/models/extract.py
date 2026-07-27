"""Versioned DTOs for POST /internal/ai/extract."""

from typing import Literal

from pydantic import Field, model_validator

from edupilot_ai.models.turn import ContractModel


class ExtractedPage(ContractModel):
    """Text extracted from one 1-based PDF page."""

    page_number: int = Field(ge=1)
    text: str


class ExtractResponse(ContractModel):
    """Complete deterministic extraction result."""

    schema_version: Literal["1.0"] = "1.0"
    page_count: int = Field(ge=1)
    pages: list[ExtractedPage]

    @model_validator(mode="after")
    def validate_pages(self) -> ExtractResponse:
        """Keep pageCount, array length, and 1-based ordering aligned."""
        expected_numbers = list(range(1, self.page_count + 1))
        actual_numbers = [page.page_number for page in self.pages]
        if len(self.pages) != self.page_count or actual_numbers != expected_numbers:
            raise ValueError("pages must contain every page in 1-based order")
        return self
