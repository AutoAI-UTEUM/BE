"""Provider-neutral token and cost aggregation for internal response contracts."""

from collections.abc import Sequence

from edupilot_ai.llm.bridge import LlmUsage
from edupilot_ai.models.base import Usage


def unknown_llm_usage(model: str | None) -> LlmUsage:
    """Represent a completed/ambiguous provider call whose counters are unavailable."""
    return LlmUsage(
        model=model,
        input_tokens=None,
        output_tokens=None,
        reasoning_tokens=None,
    )


def combine_llm_usages(
    values: Sequence[LlmUsage],
    *,
    default_model: str | None = None,
) -> LlmUsage:
    """Combine calls without treating missing counters or costs as zero."""
    if not values:
        return LlmUsage(
            model=default_model,
            input_tokens=0,
            output_tokens=0,
            reasoning_tokens=None,
        )

    models = {value.model for value in values if value.model is not None}
    model = next(iter(models)) if len(models) == 1 else None
    if any(value.model is None for value in values):
        model = None

    input_tokens = (
        None
        if any(value.input_tokens is None for value in values)
        else sum(value.input_tokens for value in values if value.input_tokens is not None)
    )
    output_tokens = (
        None
        if any(value.output_tokens is None for value in values)
        else sum(value.output_tokens for value in values if value.output_tokens is not None)
    )
    reasoning_tokens = (
        None
        if any(value.reasoning_tokens is None for value in values)
        else sum(value.reasoning_tokens for value in values if value.reasoning_tokens is not None)
    )
    cost_usd_ticks = (
        None
        if any(value.cost_usd_ticks is None for value in values)
        else sum(value.cost_usd_ticks for value in values if value.cost_usd_ticks is not None)
    )
    return LlmUsage(
        model=model,
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        reasoning_tokens=reasoning_tokens,
        cost_usd_ticks=cost_usd_ticks,
    )


def response_usage(
    values: Sequence[LlmUsage],
    *,
    default_model: str | None = None,
    include_zero_when_empty: bool = False,
) -> Usage | None:
    """Return known totals; cost can be available independently of token counts."""
    if not values and not include_zero_when_empty:
        return None
    combined = combine_llm_usages(values, default_model=default_model)
    if (
        combined.input_tokens is None or combined.output_tokens is None
    ) and combined.cost_usd_ticks is None:
        return None
    return Usage(
        model=combined.model,
        input_tokens=combined.input_tokens,
        output_tokens=combined.output_tokens,
        reasoning_tokens=combined.reasoning_tokens,
        cost_usd_ticks=combined.cost_usd_ticks,
    )
