"""Deterministic PDF extraction endpoint."""

from http import HTTPStatus
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Annotated

from fastapi import APIRouter, Depends, File, UploadFile

from edupilot_ai.api.deps import get_settings
from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.extraction import (
    PdfExtractionError,
    PdfFailureReason,
    PdfPageLimitError,
    extract_pdf,
)
from edupilot_ai.models.extract import ExtractedPage, ExtractResponse
from edupilot_ai.settings import Settings

router = APIRouter(prefix="/internal/ai")

_UPLOAD_CHUNK_BYTES = 64 * 1024
_PDF_MAGIC = b"%PDF-"
_PDF_CONTENT_TYPE = "application/pdf"

_FAILURE_MESSAGES = {
    PdfFailureReason.CORRUPTED: "PDF extraction failed because the file is invalid or corrupted.",
    PdfFailureReason.ENCRYPTED: "PDF extraction failed because the file is encrypted.",
    PdfFailureReason.NO_TEXT: "PDF extraction failed because no text layer was found.",
}


def _extraction_failure(reason: PdfFailureReason) -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.BAD_REQUEST,
        code="EXTRACTION_FAILED",
        category=ErrorCategory.INTERNAL,
        message=_FAILURE_MESSAGES[reason],
        retryable=False,
    )


def _validate_metadata(upload: UploadFile) -> None:
    filename = upload.filename or ""
    if Path(filename).suffix.lower() != ".pdf" or upload.content_type != _PDF_CONTENT_TYPE:
        raise _extraction_failure(PdfFailureReason.CORRUPTED)


def _delete_temporary(path: Path) -> None:
    path.unlink(missing_ok=True)


async def _stage_upload(upload: UploadFile, *, max_bytes: int) -> Path:
    """Copy one upload to a temporary path while enforcing an early size limit."""
    temporary = NamedTemporaryFile(prefix="edupilot-extract-", suffix=".pdf", delete=False)
    path = Path(temporary.name)
    total_bytes = 0
    first_chunk = True

    try:
        with temporary:
            while chunk := await upload.read(_UPLOAD_CHUNK_BYTES):
                if first_chunk:
                    first_chunk = False
                    if not chunk.startswith(_PDF_MAGIC):
                        raise _extraction_failure(PdfFailureReason.CORRUPTED)
                total_bytes += len(chunk)
                if total_bytes > max_bytes:
                    raise InternalApiError(
                        status_code=HTTPStatus.CONTENT_TOO_LARGE,
                        code="FILE_TOO_LARGE",
                        category=ErrorCategory.SCHEMA,
                        message="PDF exceeds the configured upload size limit.",
                        retryable=False,
                    )
                temporary.write(chunk)
    except Exception:
        _delete_temporary(path)
        raise

    if total_bytes == 0:
        _delete_temporary(path)
        raise _extraction_failure(PdfFailureReason.CORRUPTED)
    return path


@router.post("/extract", response_model=ExtractResponse)
async def extract_document(
    file: Annotated[UploadFile, File(description="PDF document to extract")],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ExtractResponse:
    """Return complete page text without persisting the PDF or extracted content."""
    temporary_path: Path | None = None
    try:
        _validate_metadata(file)
        temporary_path = await _stage_upload(file, max_bytes=settings.upload_max_bytes)
        try:
            document = extract_pdf(
                temporary_path,
                max_pages=settings.edupilot_extract_max_pages,
            )
        except PdfPageLimitError as exception:
            raise InternalApiError(
                status_code=HTTPStatus.BAD_REQUEST,
                code="PAGE_LIMIT_EXCEEDED",
                category=ErrorCategory.SCHEMA,
                message="PDF exceeds the configured page limit.",
                retryable=False,
            ) from exception
        except PdfExtractionError as exception:
            raise _extraction_failure(exception.reason) from exception

        return ExtractResponse(
            page_count=document.page_count,
            pages=[
                ExtractedPage(page_number=page.page_number, text=page.text)
                for page in document.pages
            ],
        )
    finally:
        await file.close()
        if temporary_path is not None:
            _delete_temporary(temporary_path)
