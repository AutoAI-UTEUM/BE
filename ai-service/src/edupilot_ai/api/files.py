"""Internal xAI file cleanup endpoint."""

from http import HTTPStatus
from typing import Annotated

from fastapi import APIRouter, Depends, Path, Response

from edupilot_ai.api.deps import get_xai_file_client
from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.llm.files import XaiFileClientError, XaiFileClientProtocol

router = APIRouter(prefix="/internal/ai")


@router.delete("/files/{fileId}", status_code=HTTPStatus.NO_CONTENT)
async def delete_xai_file(
    file_id: Annotated[str, Path(alias="fileId", min_length=1)],
    file_client: Annotated[XaiFileClientProtocol, Depends(get_xai_file_client)],
) -> Response:
    """Delete one provider file idempotently, including when the kill switch is off."""

    try:
        await file_client.delete(file_id)
    except XaiFileClientError as exception:
        raise InternalApiError(
            status_code=HTTPStatus.BAD_GATEWAY,
            code="FILE_DELETE_FAILED",
            category=ErrorCategory.INTERNAL,
            message="The provider file could not be deleted.",
            retryable=True,
        ) from exception
    return Response(status_code=HTTPStatus.NO_CONTENT)
