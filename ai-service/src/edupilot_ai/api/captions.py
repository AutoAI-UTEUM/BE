"""Internal page caption endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_caption_service
from edupilot_ai.captions.service import CaptionService
from edupilot_ai.models.captions import CaptionsRequest, CaptionsResponse

router = APIRouter(prefix="/internal/ai")


@router.post("/captions", response_model=CaptionsResponse)
async def create_captions(
    request: CaptionsRequest,
    service: Annotated[CaptionService, Depends(get_caption_service)],
) -> CaptionsResponse:
    return await service.execute(request)
