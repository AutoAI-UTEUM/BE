"""Deterministic PDF extraction endpoint."""

import logging
from http import HTTPStatus
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Annotated

from anyio import to_thread
from fastapi import APIRouter, Depends, File, UploadFile

from edupilot_ai.api.deps import get_settings, get_xai_file_client
from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.extraction import (
    PdfExtractionError,
    PdfFailureReason,
    PdfPageLimitError,
    extract_pdf,
)
from edupilot_ai.llm.files import (
    XAI_FILE_MAX_BYTES,
    XaiFileClientError,
    XaiFileClientProtocol,
)
from edupilot_ai.models.extract import ExtractedPage, ExtractResponse, ExtractWarning
from edupilot_ai.settings import Settings

router = APIRouter(prefix="/internal/ai")
logger = logging.getLogger(__name__)

_UPLOAD_CHUNK_BYTES = 64 * 1024
_PDF_MAGIC = b"%PDF-"
_PDF_CONTENT_TYPE = "application/pdf"
_FILE_UPLOAD_WARNING_MESSAGE = "PDF extraction succeeded, but file upload failed."

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


def _validate_metadata(
    upload: UploadFile,
    *,
    log_message: str = "PDF extraction failed",
) -> None:
    filename = upload.filename or ""
    if Path(filename).suffix.lower() != ".pdf" or upload.content_type != _PDF_CONTENT_TYPE:
        error = _unsupported_format_failure()
        logger.warning(
            log_message,
            extra={
                "errorCode": error.code,
                "status": int(error.status_code),
                "sizeBytes": _upload_size(upload),
            },
        )
        raise error


def _delete_temporary(path: Path) -> None:
    path.unlink(missing_ok=True)


def _file_upload_warning(*, size_bytes: int) -> ExtractWarning:
    logger.warning(
        "xAI file upload unavailable after extraction",
        extra={
            "errorCode": "FILE_UPLOAD_FAILED",
            "sizeBytes": size_bytes,
        },
    )
    return ExtractWarning(
        type="FILE_UPLOAD_FAILED",
        message=_FILE_UPLOAD_WARNING_MESSAGE,
    )


async def _upload_original_pdf(
    *,
    path: Path,
    filename: str,
    size_bytes: int,
    file_client: XaiFileClientProtocol,
) -> tuple[str | None, list[ExtractWarning]]:
    if size_bytes > XAI_FILE_MAX_BYTES:
        return None, [_file_upload_warning(size_bytes=size_bytes)]
    try:
        content = await to_thread.run_sync(path.read_bytes)
        file_id = await file_client.upload(content, filename or "document.pdf")
        if not file_id.strip():
            raise XaiFileClientError("FILE_UPLOAD_FAILED")
    except OSError, XaiFileClientError:
        return None, [_file_upload_warning(size_bytes=size_bytes)]
    return file_id, []


async def _stage_upload(
    upload: UploadFile,
    *,
    max_bytes: int,
    log_message: str = "PDF extraction failed",
) -> Path:
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
                        error = _unsupported_format_failure()
                        logger.warning(
                            log_message,
                            extra={
                                "errorCode": error.code,
                                "status": int(error.status_code),
                                "sizeBytes": _upload_size(upload),
                            },
                        )
                        raise error
                total_bytes += len(chunk)
                if total_bytes > max_bytes:
                    error = InternalApiError(
                        status_code=HTTPStatus.CONTENT_TOO_LARGE,
                        code="FILE_TOO_LARGE",
                        category=ErrorCategory.SCHEMA,
                        message="PDF exceeds the configured upload size limit.",
                        retryable=False,
                    )
                    logger.warning(
                        log_message,
                        extra={
                            "errorCode": error.code,
                            "status": int(error.status_code),
                            "sizeBytes": _upload_size(upload) or total_bytes,
                        },
                    )
                    raise error
                temporary.write(chunk)
    except Exception:
        _delete_temporary(path)
        raise

    if total_bytes == 0:
        _delete_temporary(path)
        error = _unsupported_format_failure()
        logger.warning(
            log_message,
            extra={
                "errorCode": error.code,
                "status": int(error.status_code),
                "sizeBytes": 0,
            },
        )
        raise error
    return path


@router.post("/extract", response_model=ExtractResponse)
async def extract_document(
    file: Annotated[UploadFile, File(description="PDF document to extract")],
    settings: Annotated[Settings, Depends(get_settings)],
    file_client: Annotated[XaiFileClientProtocol, Depends(get_xai_file_client)],
) -> ExtractResponse:
    """Return complete page text without persisting the PDF or extracted content."""
    temporary_path: Path | None = None
    size_bytes: int | None = None
    try:
        _validate_metadata(file)
        temporary_path = await _stage_upload(file, max_bytes=settings.upload_max_bytes)
        size_bytes = temporary_path.stat().st_size
        try:
            document = extract_pdf(
                temporary_path,
                max_pages=settings.edupilot_extract_max_pages,
                min_chars_per_page=settings.edupilot_extract_min_chars_per_page,
                min_meaningful_page_ratio=settings.edupilot_extract_min_meaningful_page_ratio,
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

        xai_file_id: str | None = None
        warnings: list[ExtractWarning] = []
        if settings.edupilot_xai_files_enabled:
            xai_file_id, warnings = await _upload_original_pdf(
                path=temporary_path,
                filename=file.filename or "document.pdf",
                size_bytes=size_bytes,
                file_client=file_client,
            )

        return ExtractResponse(
            page_count=document.page_count,
            pages=[
                ExtractedPage(page_number=page.page_number, text=page.text)
                for page in document.pages
            ],
            xai_file_id=xai_file_id,
            warnings=warnings,
        )
    finally:
        await file.close()
        if temporary_path is not None:
            _delete_temporary(temporary_path)
