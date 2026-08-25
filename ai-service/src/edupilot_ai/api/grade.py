"""Internal SHORT/ESSAY grading endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_grade_service
from edupilot_ai.grading.service import GradeService
from edupilot_ai.models.grading import GradeRequest, GradeResponse

router = APIRouter(prefix="/internal/ai")


@router.post("/grade", response_model=GradeResponse)
async def grade_open_responses(
    request: GradeRequest,
    service: Annotated[GradeService, Depends(get_grade_service)],
) -> GradeResponse:
    """Grade one SHORT or ESSAY submission by stable questionId."""
    return await service.execute(request)
