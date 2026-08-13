"""Deterministic PDF extraction endpoint."""

import logging
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
logger = logging.getLogger(__name__)

_UPLOAD_CHUNK_BYTES = 64 * 1024
_PDF_MAGIC = b"%PDF-"
_PDF_CONTENT_TYPE = "application/pdf"

_FAILURE_MESSAGES = {
    PdfFailureReason.CORRUPTED: "PDF extraction failed because the file is invalid or corrupted.",
    PdfFailureReason.ENCRYPTED: "PDF extraction failed because the file is encrypted.",
    PdfFailureReason.NO_TEXT: "PDF extraction failed because no text layer was found.",
}

_FAILURE_CODES = {
    PdfFailureReason.CORRUPTED: "UNSUPPORTED_FORMAT",
    PdfFailureReason.ENCRYPTED: "ENCRYPTED_PDF",
    PdfFailureReason.NO_TEXT: "NO_TEXT_CONTENT",
}


def _extraction_failure(reason: PdfFailureReason) -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.BAD_REQUEST,
        code=_FAILURE_CODES[reason],
        category=ErrorCategory.INTERNAL,
        message=_FAILURE_MESSAGES[reason],
        retryable=False,
    )


def _unsupported_format_failure() -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.BAD_REQUEST,
        code="UNSUPPORTED_FORMAT",
        category=ErrorCategory.INTERNAL,
        message=_FAILURE_MESSAGES[PdfFailureReason.CORRUPTED],
        retryable=False,
    )


def _logged_extraction_failure(
    error: InternalApiError,
    *,
    size_bytes: int | None = None,
    page_count: int | None = None,
) -> InternalApiError:
    extra: dict[str, object] = {
        "errorCode": error.code,
        "status": int(error.status_code),
    }
    if size_bytes is not None:
        extra["sizeBytes"] = size_bytes
    if page_count is not None:
        extra["pageCount"] = page_count
    logger.warning("PDF extraction failed", extra=extra)
    return error


def _upload_size(upload: UploadFile) -> int | None:
    size = upload.size
    return size if isinstance(size, int) and size >= 0 else None


def _validate_metadata(upload: UploadFile) -> None:
    filename = upload.filename or ""
    if Path(filename).suffix.lower() != ".pdf" or upload.content_type != _PDF_CONTENT_TYPE:
        raise _logged_extraction_failure(
            _unsupported_format_failure(),
            size_bytes=_upload_size(upload),
        )


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
                        raise _logged_extraction_failure(
                            _unsupported_format_failure(),
                            size_bytes=_upload_size(upload),
                        )
                total_bytes += len(chunk)
                if total_bytes > max_bytes:
                    raise _logged_extraction_failure(
                        InternalApiError(
                            status_code=HTTPStatus.CONTENT_TOO_LARGE,
                            code="FILE_TOO_LARGE",
                            category=ErrorCategory.SCHEMA,
                            message="PDF exceeds the configured upload size limit.",
                            retryable=False,
                        ),
                        size_bytes=_upload_size(upload) or total_bytes,
                    )
                temporary.write(chunk)
    except Exception:
        _delete_temporary(path)
        raise

    if total_bytes == 0:
        _delete_temporary(path)
        raise _logged_extraction_failure(
            _unsupported_format_failure(),
            size_bytes=0,
        )
    return path


@router.post("/extract", response_model=ExtractResponse)
async def extract_document(
    file: Annotated[UploadFile, File(description="PDF document to extract")],
    settings: Annotated[Settings, Depends(get_settings)],
) -> ExtractResponse:
    """Return complete page text without persisting the PDF or extracted content."""
    temporary_path: Path | None = None
    size_bytes: int | None = None
    try:
        _validate_metadata(file)
        temporary_path = await _stage_upload(file, max_bytes=settings.upload_max_bytes)
        size_bytes = _upload_size(file)
        try:
            document = extract_pdf(
                temporary_path,
                max_pages=settings.edupilot_extract_max_pages,
            )
        except PdfPageLimitError as exception:
            raise _logged_extraction_failure(
                InternalApiError(
                    status_code=HTTPStatus.BAD_REQUEST,
                    code="PAGE_LIMIT_EXCEEDED",
                    category=ErrorCategory.SCHEMA,
                    message="PDF exceeds the configured page limit.",
                    retryable=False,
                ),
                size_bytes=size_bytes,
                page_count=exception.page_count,
            ) from exception
        except PdfExtractionError as exception:
            raise _logged_extraction_failure(
                _extraction_failure(exception.reason),
                size_bytes=size_bytes,
            ) from exception

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
