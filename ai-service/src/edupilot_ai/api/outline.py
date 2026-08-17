"""Internal material outline endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_outline_service
from edupilot_ai.models.outline import OutlineRequest, OutlineResponse
from edupilot_ai.outline.service import OutlineService

router = APIRouter(prefix="/internal/ai")


@router.post("/outline", response_model=OutlineResponse)
async def create_outline(
    request: OutlineRequest,
    service: Annotated[OutlineService, Depends(get_outline_service)],
) -> OutlineResponse:
    return await service.execute(request)
