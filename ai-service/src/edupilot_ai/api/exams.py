"""Internal stateless exam draft endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_exam_draft_service
from edupilot_ai.examdraft.service import ExamDraftService
from edupilot_ai.models.exam_draft import ExamDraftRequest, ExamDraftResponse

router = APIRouter(prefix="/internal/ai/exams")


@router.post("/draft", response_model=ExamDraftResponse)
async def create_exam_draft(
    request: ExamDraftRequest,
    service: Annotated[ExamDraftService, Depends(get_exam_draft_service)],
) -> ExamDraftResponse:
    return await service.execute(request)
