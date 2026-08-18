"""Internal classroom criterion suggestion endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_criteria_suggest_service
from edupilot_ai.criteria.service import CriteriaSuggestService
from edupilot_ai.models.criteria import CriteriaSuggestRequest, CriteriaSuggestResponse

router = APIRouter(prefix="/internal/ai")


@router.post("/criteria/suggest", response_model=CriteriaSuggestResponse)
async def suggest_criteria(
    request: CriteriaSuggestRequest,
    service: Annotated[CriteriaSuggestService, Depends(get_criteria_suggest_service)],
) -> CriteriaSuggestResponse:
    return await service.execute(request)
