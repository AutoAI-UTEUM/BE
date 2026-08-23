"""Internal lightweight document question-answering endpoint."""

from typing import Annotated

from fastapi import APIRouter, Depends

from edupilot_ai.api.deps import get_doc_chat_service
from edupilot_ai.docchat.service import DocChatService
from edupilot_ai.models.doc_chat import DocChatRequest, DocChatResponse

router = APIRouter(prefix="/internal/ai")


@router.post("/doc-chat", response_model=DocChatResponse)
async def doc_chat(
    request: DocChatRequest,
    service: Annotated[DocChatService, Depends(get_doc_chat_service)],
) -> DocChatResponse:
    return await service.execute(request)
