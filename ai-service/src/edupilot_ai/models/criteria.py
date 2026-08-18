"""Strict contracts for classroom criterion suggestions."""

import re
from typing import Literal, Self

from pydantic import Field, model_validator

from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.outline import OutlineSection
from edupilot_ai.models.report import EvidenceSourceType


class CriteriaMaterial(ContractModel):
    title: str = Field(min_length=1)
    material_summary: str
    sections: list[OutlineSection]


class CriteriaSuggestRequest(ContractModel):
    schema_version: Literal["1.0"]
    existing_criterion_keys: list[str] = Field(min_length=1)
    materials: list[CriteriaMaterial]

    @model_validator(mode="after")
    def validate_existing_keys(self) -> Self:
        if len(self.existing_criterion_keys) != len(set(self.existing_criterion_keys)):
            raise ValueError("existingCriterionKeys values must be unique")
        return self


class SuggestedCriterion(ContractModel):
    key: str
    name: str
    description: str
    rubric: str
    allowed_sources: list[EvidenceSourceType]
    weight: float = Field(default=1.0, ge=1.0, le=1.0)
    minimum_evidence: Literal[2] = 2


class CriteriaWarning(ContractModel):
    type: str = Field(min_length=1)
    message: str = Field(min_length=1)


class CriteriaSuggestOutput(ContractModel):
    criteria: list[SuggestedCriterion]
    warnings: list[CriteriaWarning] = Field(default_factory=list)


class CriteriaSuggestResponse(CriteriaSuggestOutput):
    schema_version: Literal["1.0"] = "1.0"


CRITERION_KEY_PATTERN = re.compile(r"^[a-z][a-z0-9_]{2,50}$")
