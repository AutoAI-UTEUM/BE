"""Internal assessment and diagnosis endpoints."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import (
    get_quiz_assessment_service,
    get_quiz_diagnosis_service,
)
from edupilot_ai.models.learning_support import (
    AssessmentRequest,
    AssessmentResponse,
    DiagnosisRequest,
    DiagnosisResponse,
)
from edupilot_ai.support.service import QuizAssessmentService, QuizDiagnosisService

router = APIRouter(prefix="/internal/ai")


@router.post("/quiz-assessment", response_model=AssessmentResponse)
async def create_quiz_assessment(
    request: AssessmentRequest,
    service: Annotated[
        QuizAssessmentService,
        Depends(get_quiz_assessment_service),
    ],
) -> AssessmentResponse:
    return await service.execute(request)


@router.post("/diagnosis", response_model=DiagnosisResponse)
async def create_diagnosis(
    request: DiagnosisRequest,
    service: Annotated[
        QuizDiagnosisService,
        Depends(get_quiz_diagnosis_service),
    ],
) -> DiagnosisResponse:
    return await service.execute(request)
