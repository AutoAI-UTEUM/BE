"""Internal incremental conversation summary endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_conversation_summary_service
from edupilot_ai.models.conversation_summary import (
    ConversationSummaryRequest,
    ConversationSummaryResponse,
)
from edupilot_ai.summary.service import ConversationSummaryService

router = APIRouter(prefix="/internal/ai")


@router.post("/conversation-summary", response_model=ConversationSummaryResponse)
async def conversation_summary(
    request: ConversationSummaryRequest,
    service: Annotated[
        ConversationSummaryService,
        Depends(get_conversation_summary_service),
    ],
) -> ConversationSummaryResponse:
    return await service.execute(request)
