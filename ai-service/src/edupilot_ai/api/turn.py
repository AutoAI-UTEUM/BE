"""Bootstrap implementation of the internal AI turn endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_settings
from edupilot_ai.models.turn import Message, TurnRequest, TurnResponse, Usage
from edupilot_ai.settings import Settings

router = APIRouter(prefix="/internal/ai")


@router.post("/turn", response_model=TurnResponse)
def execute_turn(
    turn: TurnRequest,
    settings: Annotated[Settings, Depends(get_settings)],
) -> TurnResponse:
    """Return the fixed issue #9 stub response without invoking an LLM."""
    return TurnResponse(
        turn_id=turn.turn_id,
        turn_goal="ANSWER_USER_QUESTION",
        actions_executed=[],
        messages=[
            Message(
                message_type="SYSTEM",
                content="EduPilot AI turn stub is ready.",
            )
        ],
        state_patch={},
        ui_actions=[],
        memory_candidates=[],
        usage=Usage(
            model=settings.model_name,
            input_tokens=0,
            output_tokens=0,
            reasoning_tokens=None,
        ),
    )
