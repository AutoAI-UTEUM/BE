"""Internal evidence-bound report endpoints."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import (
    get_report_generation_service,
    get_report_query_service,
)
from edupilot_ai.models.report import (
    ReportGenerateRequest,
    ReportGenerateResponse,
    ReportQueryRequest,
    ReportQueryResponse,
)
from edupilot_ai.reporting.service import ReportGenerationService, ReportQueryService

router = APIRouter(prefix="/internal/ai")


@router.post("/reports/generate", response_model=ReportGenerateResponse)
async def generate_report(
    request: ReportGenerateRequest,
    service: Annotated[
        ReportGenerationService,
        Depends(get_report_generation_service),
    ],
) -> ReportGenerateResponse:
    return await service.execute(request)


@router.post("/reports/query", response_model=ReportQueryResponse)
async def query_report(
    request: ReportQueryRequest,
    service: Annotated[
        ReportQueryService,
        Depends(get_report_query_service),
    ],
) -> ReportQueryResponse:
    return await service.execute(request)
