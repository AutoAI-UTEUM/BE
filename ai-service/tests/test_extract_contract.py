"""HTTP contract tests for POST /internal/ai/extract."""

import logging

import httpx
import pytest
from pydantic import TypeAdapter

from edupilot_ai.core.errors import InternalErrorResponse
from edupilot_ai.llm.files import XaiFileClientError
from edupilot_ai.models.extract import ExtractResponse
from edupilot_ai.settings import Settings
from tests.fakes import FakeXaiFileClient
from tests.pdf_factory import make_blank_pdf, make_pdf


def test_extract_quality_threshold_defaults(settings: Settings) -> None:
    assert settings.edupilot_extract_min_chars_per_page == 50
    assert settings.edupilot_extract_min_meaningful_page_ratio == 0.05
    assert settings.edupilot_xai_files_enabled is False
    assert settings.edupilot_xai_file_upload_timeout_seconds == 60


async def test_extract_returns_versioned_pages(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    fake_file_client: FakeXaiFileClient,
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={
            "file": (
                "lesson.pdf",
                make_pdf(
                    "First page contains enough explanatory text for normal extraction.",
                    None,
                ),
                "application/pdf",
            )
        },
    )

    assert response.status_code == 200
    assert response.json() == {
        "schemaVersion": "1.0",
        "pageCount": 2,
        "pages": [
            {
                "pageNumber": 1,
                "text": "First page contains enough explanatory text for normal extraction.",
            },
            {"pageNumber": 2, "text": ""},
        ],
        "xaiFileId": None,
        "warnings": [],
        "usage": None,
    }
    ExtractResponse.model_validate(response.json())
    assert fake_file_client.uploads == []


async def test_extract_uploads_original_pdf_when_xai_files_enabled(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
) -> None:
    settings.edupilot_xai_files_enabled = True
    fake_file_client.upload_result = "file-live-contract"
    pdf = make_pdf("A readable PDF page with enough text for extraction and upload.")

    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("private-lesson.pdf", pdf, "application/pdf")},
    )

    assert response.status_code == 200
    assert response.json()["xaiFileId"] == "file-live-contract"
    assert response.json()["warnings"] == []
    assert fake_file_client.uploads == [(pdf, "private-lesson.pdf")]


async def test_extract_keeps_success_when_xai_file_upload_fails(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
) -> None:
    settings.edupilot_xai_files_enabled = True
    fake_file_client.upload_error = XaiFileClientError("FILE_UPLOAD_FAILED")
    pdf = make_pdf("A readable PDF page whose extraction must still succeed after upload failure.")

    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("fallback.pdf", pdf, "application/pdf")},
    )

    assert response.status_code == 200
    body = ExtractResponse.model_validate(response.json())
    assert body.page_count == 1
    assert body.pages[0].text.startswith("A readable PDF page")
    assert body.xai_file_id is None
    assert [warning.model_dump() for warning in body.warnings] == [
        {
            "type": "FILE_UPLOAD_FAILED",
            "message": "PDF extraction succeeded, but file upload failed.",
        }
    ]


async def test_extract_upload_failure_log_excludes_filename_and_content(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
    caplog: pytest.LogCaptureFixture,
) -> None:
    settings.edupilot_xai_files_enabled = True
    fake_file_client.upload_error = XaiFileClientError("FILE_UPLOAD_FAILED")
    private_filename = "PRIVATE-UPLOAD-NAME.pdf"
    private_text = "PRIVATE-PDF-BODY with enough text to pass extraction checks."
    pdf = make_pdf(private_text)
    extract_logger = logging.getLogger("edupilot_ai.api.extract")
    extract_logger.addHandler(caplog.handler)
    try:
        response = await client.post(
            "/internal/ai/extract",
            headers=auth_headers,
            files={"file": (private_filename, pdf, "application/pdf")},
        )
    finally:
        extract_logger.removeHandler(caplog.handler)

    assert response.status_code == 200
    record = next(
        item
        for item in caplog.records
        if item.message == "xAI file upload unavailable after extraction"
    )
    assert record.__dict__["errorCode"] == "FILE_UPLOAD_FAILED"
    assert record.__dict__["sizeBytes"] == len(pdf)
    assert private_filename not in caplog.text
    assert private_text not in caplog.text


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


async def test_extract_rejects_negligible_symbol_text_across_pages(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={
            "file": (
                "image-only.pdf",
                make_pdf("*", "**", "***", "*", "**", "***", "*", "**", "***", "*"),
                "application/pdf",
            )
        },
    )

    assert_extraction_failure(
        response,
        code="NO_TEXT_CONTENT",
        reason_phrase="no text layer",
    )


async def test_extract_accepts_sparse_document_at_meaningful_page_ratio_boundary(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    meaningful_text = "A" * 50
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={
            "file": (
                "sparse-but-readable.pdf",
                make_pdf(meaningful_text, *(None for _ in range(9))),
                "application/pdf",
            )
        },
    )

    assert response.status_code == 200
    body = ExtractResponse.model_validate(response.json())
    assert body.page_count == 10
    assert body.pages[0].text == meaningful_text


async def test_extract_rejects_single_page_with_negligible_text(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
) -> None:
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("short.pdf", make_pdf("12345"), "application/pdf")},
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
                make_pdf(*("B" * 50 for _ in range(15)), *(None for _ in range(285))),
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
    pdf = make_pdf("At the byte boundary with enough text to pass extraction checks." * 2)
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
