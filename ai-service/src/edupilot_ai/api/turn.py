"""Internal AI turn endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_turn_service
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.service import TurnService

router = APIRouter(prefix="/internal/ai")


@router.post("/turn", response_model=TurnResponse)
async def execute_turn(
    turn: TurnRequest,
    service: Annotated[TurnService, Depends(get_turn_service)],
) -> TurnResponse:
    """Execute one validated non-streaming turn."""
    return await service.execute(turn)
