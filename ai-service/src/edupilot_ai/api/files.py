"""Internal xAI file upload and cleanup endpoints."""

from http import HTTPStatus
from typing import Annotated

from anyio import to_thread
from fastapi import APIRouter, Depends, File, Path, Response, UploadFile

from edupilot_ai.api.deps import get_xai_file_client
from edupilot_ai.api.extract import (
    _delete_temporary,
    _stage_upload,
    _validate_metadata,
)
from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.llm.files import (
    XAI_FILE_MAX_BYTES,
    XaiFileClientError,
    XaiFileClientProtocol,
)
from edupilot_ai.models.files import XaiFileUploadResponse

router = APIRouter(prefix="/internal/ai")


@router.post("/files", response_model=XaiFileUploadResponse)
async def upload_xai_file(
    file: Annotated[UploadFile, File(description="PDF document to upload")],
    file_client: Annotated[XaiFileClientProtocol, Depends(get_xai_file_client)],
) -> XaiFileUploadResponse:
    """Upload a validated PDF without extracting text or changing material state."""

    temporary_path = None
    try:
        _validate_metadata(file, log_message="PDF file upload rejected")
        temporary_path = await _stage_upload(
            file,
            max_bytes=XAI_FILE_MAX_BYTES,
            log_message="PDF file upload rejected",
        )
        try:
            content = await to_thread.run_sync(temporary_path.read_bytes)
            file_id = await file_client.upload(
                content,
                file.filename or "material.pdf",
            )
            return XaiFileUploadResponse(xai_file_id=file_id)
        except (OSError, XaiFileClientError, ValueError) as exception:
            raise InternalApiError(
                status_code=HTTPStatus.BAD_GATEWAY,
                code="FILE_UPLOAD_FAILED",
                category=ErrorCategory.INTERNAL,
                message="The provider file could not be uploaded.",
                retryable=getattr(exception, "retryable", True),
            ) from exception
    finally:
        await file.close()
        if temporary_path is not None:
            _delete_temporary(temporary_path)


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
