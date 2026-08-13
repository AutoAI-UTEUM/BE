"""HTTP contract tests for POST /internal/ai/extract."""

import logging

import httpx
import pytest
from pydantic import TypeAdapter

from edupilot_ai.core.errors import InternalErrorResponse
from edupilot_ai.models.extract import ExtractResponse
from edupilot_ai.settings import Settings
from tests.pdf_factory import make_blank_pdf, make_pdf


async def test_extract_returns_versioned_pages(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("lesson.pdf", make_pdf("First page", None), "application/pdf")},
    )

    assert response.status_code == 200
    assert response.json() == {
        "schemaVersion": "1.0",
        "pageCount": 2,
        "pages": [
            {"pageNumber": 1, "text": "First page"},
            {"pageNumber": 2, "text": ""},
        ],
    }
    ExtractResponse.model_validate(response.json())


async def test_extract_requires_internal_token(
    client: httpx.AsyncClient,
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        files={"file": ("lesson.pdf", make_pdf("text"), "application/pdf")},
    )

    assert response.status_code == 401
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.category == "AUTH"


async def test_extract_rejects_non_pdf_extension(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("lesson.ppt", make_pdf("text"), "application/pdf")},
    )

    assert_extraction_failure(
        response,
        code="UNSUPPORTED_FORMAT",
        reason_phrase="invalid or corrupted",
    )


async def test_extract_rejects_non_pdf_magic_bytes(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("lesson.pdf", b"not-a-pdf", "application/pdf")},
    )

    assert_extraction_failure(
        response,
        code="UNSUPPORTED_FORMAT",
        reason_phrase="invalid or corrupted",
    )


async def test_extract_rejects_empty_upload_as_unsupported_format(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("lesson.pdf", b"", "application/pdf")},
    )

    assert_extraction_failure(
        response,
        code="UNSUPPORTED_FORMAT",
        reason_phrase="invalid or corrupted",
    )


async def test_extract_rejects_non_pdf_content_type(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("lesson.pdf", make_pdf("text"), "application/octet-stream")},
    )

    assert_extraction_failure(
        response,
        code="UNSUPPORTED_FORMAT",
        reason_phrase="invalid or corrupted",
    )


async def test_extract_rejects_scanned_document(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("scanned.pdf", make_blank_pdf(2), "application/pdf")},
    )

    assert_extraction_failure(
        response,
        code="NO_TEXT_CONTENT",
        reason_phrase="no text layer",
    )


async def test_extract_rejects_encrypted_document(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={
            "file": (
                "encrypted.pdf",
                make_pdf("secret", password="not-a-real-secret"),
                "application/pdf",
            )
        },
    )

    assert_extraction_failure(
        response,
        code="ENCRYPTED_PDF",
        reason_phrase="encrypted",
    )


async def test_extract_rejects_corrupted_document_without_raw_error(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("broken.pdf", b"%PDF-broken", "application/pdf")},
    )

    assert_extraction_failure(
        response,
        code="UNSUPPORTED_FORMAT",
        reason_phrase="invalid or corrupted",
    )
    assert "EOF marker" not in response.text


async def test_extract_failure_log_contains_reason_without_file_data(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    caplog: pytest.LogCaptureFixture,
) -> None:
    private_filename = "PRIVATE-STUDENT-FILE.pdf"
    private_content = b"PRIVATE-PDF-CONTENT"
    extract_logger = logging.getLogger("edupilot_ai.api.extract")
    extract_logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/extract",
            headers=auth_headers,
            files={
                "file": (
                    private_filename,
                    private_content,
                    "application/pdf",
                )
            },
        )
    finally:
        extract_logger.removeHandler(caplog.handler)

    assert response.status_code == 400
    record = next(item for item in caplog.records if item.message == "PDF extraction failed")
    assert record.levelno == logging.WARNING
    assert record.__dict__["errorCode"] == "UNSUPPORTED_FORMAT"
    assert record.__dict__["status"] == 400
    assert record.__dict__["sizeBytes"] == len(private_content)
    assert private_filename not in caplog.text
    assert private_content.decode() not in caplog.text


async def test_extract_accepts_exactly_300_pages(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={
            "file": (
                "boundary.pdf",
                make_pdf("Boundary page", *(None for _ in range(299))),
                "application/pdf",
            )
        },
    )

    assert response.status_code == 200
    payload = ExtractResponse.model_validate(response.json())
    assert payload.page_count == 300
    assert payload.pages[-1].page_number == 300


async def test_extract_rejects_301_pages(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("long.pdf", make_blank_pdf(301), "application/pdf")},
    )

    assert response.status_code == 400
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "PAGE_LIMIT_EXCEEDED"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False


async def test_extract_accepts_file_equal_to_configured_size_limit(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
) -> None:
    settings.edupilot_upload_max_mb = 1
    pdf = make_pdf("At the byte boundary")
    exact_limit = pdf + (b"\x00" * (settings.upload_max_bytes - len(pdf)))

    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("boundary.pdf", exact_limit, "application/pdf")},
    )

    assert response.status_code == 200
    assert response.json()["pageCount"] == 1


async def test_extract_rejects_size_as_soon_as_configured_limit_is_exceeded(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
) -> None:
    settings.edupilot_upload_max_mb = 1
    oversized = b"%PDF-" + (b"0" * (1024 * 1024))

    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("large.pdf", oversized, "application/pdf")},
    )

    assert response.status_code == 413
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "FILE_TOO_LARGE"
    assert error.error.category == "SCHEMA"
    assert error.error.retryable is False


def assert_extraction_failure(
    response: httpx.Response,
    *,
    code: str,
    reason_phrase: str,
) -> None:
    assert response.status_code == 400
    payload = response.json()
    TypeAdapter(InternalErrorResponse).validate_python(payload)
    assert set(payload) == {"schemaVersion", "error", "traceId"}
    assert payload["schemaVersion"] == "1.0"
    assert payload["error"]["code"] == code
    assert payload["error"]["category"] == "INTERNAL"
    assert payload["error"]["retryable"] is False
    assert reason_phrase in payload["error"]["message"]
    assert payload["traceId"] == "contract-test-trace"
