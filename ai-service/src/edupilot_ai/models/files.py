"""Versioned DTOs for explicit xAI Files operations."""

from typing import Literal

from pydantic import Field, field_validator

from edupilot_ai.models.base import ContractModel


class XaiFileUploadResponse(ContractModel):
    """Provider file id returned by the upload-only internal endpoint."""

    schema_version: Literal["1.0"] = "1.0"
    xai_file_id: str = Field(min_length=1)

    @field_validator("xai_file_id")
    @classmethod
    def normalize_file_id(cls, value: str) -> str:
        """Reject a provider response that contains only whitespace."""
        normalized = value.strip()
        if not normalized:
            raise ValueError("xaiFileId must not be blank")
        return normalized
