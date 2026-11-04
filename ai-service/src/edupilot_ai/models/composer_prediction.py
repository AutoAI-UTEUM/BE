"""Optional next-message drafts; never a turn action or learner answer."""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints

from edupilot_ai.models.base import ContractModel, Usage

type NonBlankId = Annotated[str, StringConstraints(min_length=1, max_length=128, pattern=r"\S")]


class PredictionMessage(ContractModel):
    role: Literal["USER", "ASSISTANT"]
    content: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=4000)]


class ComposerPredictionRequest(ContractModel):
    schema_version: Literal["1.0"]
    source_turn_id: NonBlankId
    context_version: NonBlankId
    # Spring derives this from authoritative session state, not browser input.
    learning_activity: Literal["EXPLAIN", "QA", "QUIZ", "DIAGNOSIS", "TEACHBACK", "OTHER"]
    messages: list[PredictionMessage] = Field(min_length=1, max_length=6)


class ComposerPredictionCompletion(ContractModel):
    suggestion: str | None = Field(max_length=160)


class PredictionWarning(ContractModel):
    type: Literal["PREDICTION_UNAVAILABLE"] = "PREDICTION_UNAVAILABLE"
    message: str = "다음 질문 제안을 생략했습니다. 직접 질문을 입력할 수 있습니다."


class ComposerPredictionResponse(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    source_turn_id: NonBlankId
    context_version: NonBlankId
    suggestion: str | None = Field(default=None, max_length=160)
    warnings: list[PredictionWarning] = Field(default_factory=list)
    usage: Usage | None = None
