"""NDJSON event contract for the internal turn stream."""

from typing import Literal

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.models.turn import ContractModel, TurnResponse


class StatusStreamEvent(ContractModel):
    type: Literal["status"] = "status"
    stage: Literal["PLANNING", "EXPLAINING", "ANSWERING", "FINALIZING"]


class ThoughtSummaryStreamEvent(ContractModel):
    type: Literal["thought_summary"] = "thought_summary"
    text: str


class ContentDeltaStreamEvent(ContractModel):
    type: Literal["content_delta"] = "content_delta"
    text: str


class HeartbeatStreamEvent(ContractModel):
    type: Literal["heartbeat"] = "heartbeat"


class CompletedStreamEvent(ContractModel):
    type: Literal["completed"] = "completed"
    result: TurnResponse


class ErrorStreamEvent(ContractModel):
    type: Literal["error"] = "error"
    code: str
    category: ErrorCategory
    message: str
    retryable: bool


type TurnStreamEvent = (
    StatusStreamEvent
    | ThoughtSummaryStreamEvent
    | ContentDeltaStreamEvent
    | HeartbeatStreamEvent
    | CompletedStreamEvent
    | ErrorStreamEvent
)
