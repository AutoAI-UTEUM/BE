"""Strict contracts for page-level visual captions."""

import base64
import binascii
from typing import Literal, Self

from pydantic import Field, field_validator, model_validator

from edupilot_ai.models.base import ContractModel

MAX_CAPTION_IMAGE_BYTES = 10 * 1024 * 1024


class CaptionPageRequest(ContractModel):
    page_number: int = Field(ge=1)
    image_base64: str = Field(min_length=1)
    extracted_text: str | None = None

    @field_validator("image_base64")
    @classmethod
    def validate_image_base64(cls, value: str) -> str:
        try:
            decoded = base64.b64decode(value, validate=True)
        except binascii.Error, ValueError:
            raise ValueError("imageBase64 must be valid base64") from None
        if len(decoded) > MAX_CAPTION_IMAGE_BYTES:
            raise ValueError("decoded imageBase64 must be at most 10 MiB")
        return value


class CaptionsRequest(ContractModel):
    schema_version: Literal["1.0"]
    pages: list[CaptionPageRequest] = Field(min_length=1, max_length=10)

    @model_validator(mode="after")
    def validate_page_numbers(self) -> Self:
        page_numbers = [page.page_number for page in self.pages]
        if len(page_numbers) != len(set(page_numbers)):
            raise ValueError("pageNumber values must be unique")
        return self


class CaptionOutput(ContractModel):
    caption: str | None


class PageCaption(ContractModel):
    page_number: int = Field(ge=1)
    caption: str | None


class CaptionWarning(ContractModel):
    type: Literal["PAGE_CAPTION_FAILED"] = "PAGE_CAPTION_FAILED"
    message: str


class CaptionsResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    captions: list[PageCaption]
    warnings: list[CaptionWarning] = Field(default_factory=list)
