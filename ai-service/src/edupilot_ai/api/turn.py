"""Internal AI turn endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from edupilot_ai.api.deps import get_turn_service
from edupilot_ai.models.turn import TurnRequest, TurnResponse
from edupilot_ai.orchestration.service import TurnService

router = APIRouter(prefix="/internal/ai")


@router.post("/turn", response_model=TurnResponse)
async def execute_turn(
    request: Request,
    turn: TurnRequest,
    service: Annotated[TurnService, Depends(get_turn_service)],
) -> TurnResponse | StreamingResponse:
    """Negotiate NDJSON streaming while preserving the JSON contract."""
    accepted = {
        item.partition(";")[0].strip().lower()
        for item in request.headers.get("Accept", "").split(",")
    }
    if "application/x-ndjson" in accepted:
        return StreamingResponse(
            service.stream_ndjson(turn),
            media_type="application/x-ndjson",
            headers={
                "Cache-Control": "no-cache, no-store",
                "X-Accel-Buffering": "no",
            },
        )
    return await service.execute(turn)
