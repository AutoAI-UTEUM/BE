"""Shared model configuration for internal AI contracts."""

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ContractModel(BaseModel):
    """Camel-case, strict model used at internal service boundaries."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class Usage(ContractModel):
    """Optional provider token and cost accounting shared by internal responses."""

    model: str | None = None
    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    reasoning_tokens: int | None = Field(default=None, ge=0)
    cost_usd_ticks: int | None = Field(
        default=None,
        alias="cost_usd_ticks",
        strict=True,
        ge=0,
        exclude_if=lambda value: value is None,
    )
