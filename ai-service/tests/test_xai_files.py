"""xAI Files wire contract and internal delete endpoint tests."""

import logging

import httpx
import pytest
import respx
from pydantic import SecretStr

from edupilot_ai.core.errors import InternalErrorResponse
from edupilot_ai.llm.files import (
    XAI_FILE_MAX_BYTES,
    XAI_FILES_URL,
    XaiFileClient,
    XaiFileClientError,
)
from edupilot_ai.settings import Settings
from tests.fakes import FakeXaiFileClient


async def test_xai_file_client_uploads_multipart_and_returns_file_id(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    private_filename = "PRIVATE-LESSON.pdf"
    private_content = b"%PDF-PRIVATE-CONTENT"
    route = respx_mock.post(XAI_FILES_URL).mock(
        return_value=httpx.Response(200, json={"id": "file-contract", "object": "file"})
    )
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with caplog.at_level(logging.INFO, logger="edupilot_ai.llm.files"):
            file_id = await client.upload(private_content, private_filename)

    assert file_id == "file-contract"
    request = route.calls[0].request
    assert request.headers["Authorization"] == "Bearer xai-test-not-real"
    assert request.headers["Content-Type"].startswith("multipart/form-data; boundary=")
    assert b'name="purpose"' in request.content
    assert b"assistants" in request.content
    assert b'name="file"' in request.content
    assert private_filename.encode() in request.content
    assert private_content in request.content
    record = next(item for item in caplog.records if item.message == "xAI file upload finished")
    assert record.__dict__["fileId"] == "file-contract"
    assert record.__dict__["sizeBytes"] == len(private_content)
    assert private_filename not in caplog.text
    assert private_content.decode() not in caplog.text


async def test_xai_file_client_wraps_timeout_without_sensitive_log_data(
    caplog: pytest.LogCaptureFixture,
    respx_mock: respx.MockRouter,
) -> None:
    private_filename = "PRIVATE-TIMEOUT.pdf"
    private_content = b"%PDF-PRIVATE-TIMEOUT-CONTENT"
    respx_mock.post(XAI_FILES_URL).mock(side_effect=httpx.ReadTimeout("private error"))
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with caplog.at_level(logging.WARNING, logger="edupilot_ai.llm.files"):
            with pytest.raises(XaiFileClientError):
                await client.upload(private_content, private_filename)

    assert private_filename not in caplog.text
    assert private_content.decode() not in caplog.text
    record = next(item for item in caplog.records if item.message == "xAI file upload finished")
    assert record.__dict__["errorCode"] == "FILE_UPLOAD_FAILED"
    assert record.__dict__["sizeBytes"] == len(private_content)


@pytest.mark.parametrize("status_code", [400, 503])
async def test_xai_file_client_rejects_provider_error_statuses(
    respx_mock: respx.MockRouter,
    status_code: int,
) -> None:
    respx_mock.post(XAI_FILES_URL).mock(
        return_value=httpx.Response(status_code, json={"error": {"message": "private"}})
    )
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with pytest.raises(XaiFileClientError) as captured:
            await client.upload(b"%PDF-provider-error", "provider-error.pdf")

    assert captured.value.retryable is (status_code >= 500)


async def test_xai_file_client_rejects_blank_provider_file_id(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.post(XAI_FILES_URL).mock(return_value=httpx.Response(200, json={"id": "   "}))
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with pytest.raises(XaiFileClientError):
            await client.upload(b"%PDF-blank-id", "blank-id.pdf")


async def test_xai_file_client_rejects_content_over_provider_limit() -> None:
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with pytest.raises(XaiFileClientError):
            await client.upload(
                b"x" * (XAI_FILE_MAX_BYTES + 1),
                "oversize.pdf",
            )


async def test_xai_file_client_delete_treats_404_as_success(
    respx_mock: respx.MockRouter,
) -> None:
    route = respx_mock.delete(f"{XAI_FILES_URL}/file-missing").mock(
        return_value=httpx.Response(404, json={"error": {"message": "not found"}})
    )
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        await client.delete("file-missing")

    assert route.called
    assert route.calls[0].request.headers["Authorization"] == "Bearer xai-test-not-real"


async def test_xai_file_client_delete_wraps_provider_failure(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.delete(f"{XAI_FILES_URL}/file-provider-failure").mock(
        return_value=httpx.Response(503, json={"error": {"message": "private"}})
    )
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with pytest.raises(XaiFileClientError):
            await client.delete("file-provider-failure")


async def test_xai_file_client_delete_rejects_redirect_response(
    respx_mock: respx.MockRouter,
) -> None:
    respx_mock.delete(f"{XAI_FILES_URL}/file-redirect").mock(
        return_value=httpx.Response(307, headers={"Location": "https://example.invalid"})
    )
    async with httpx.AsyncClient() as http_client:
        client = XaiFileClient(
            client=http_client,
            api_key=SecretStr("xai-test-not-real"),
            timeout_seconds=60,
        )
        with pytest.raises(XaiFileClientError):
            await client.delete("file-redirect")


async def test_delete_endpoint_returns_204_even_when_kill_switch_is_off(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
) -> None:
    assert settings.edupilot_xai_files_enabled is False

    response = await client.delete(
        "/internal/ai/files/file-delete-contract",
        headers=auth_headers,
    )

    assert response.status_code == 204
    assert response.content == b""
    assert fake_file_client.deletes == ["file-delete-contract"]


async def test_upload_endpoint_returns_nonblank_file_id_when_kill_switch_is_off(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
) -> None:
    assert settings.edupilot_xai_files_enabled is False
    fake_file_client.upload_result = "  file-backfill-contract  "

    response = await client.post(
        "/internal/ai/files",
        headers=auth_headers,
        files={"file": ("private.pdf", b"%PDF-backfill", "application/pdf")},
    )

    assert response.status_code == 200
    assert response.json() == {
        "schemaVersion": "1.0",
        "xaiFileId": "file-backfill-contract",
    }
    assert fake_file_client.uploads == [(b"%PDF-backfill", "private.pdf")]


async def test_upload_endpoint_requires_internal_token(
    client: httpx.AsyncClient,
    fake_file_client: FakeXaiFileClient,
) -> None:
    response = await client.post(
        "/internal/ai/files",
        files={"file": ("private.pdf", b"%PDF-private", "application/pdf")},
    )

    assert response.status_code == 401
    assert fake_file_client.uploads == []


async def test_upload_endpoint_maps_provider_failure_to_retryable_502(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    fake_file_client: FakeXaiFileClient,
) -> None:
    fake_file_client.upload_error = XaiFileClientError("FILE_UPLOAD_FAILED")

    response = await client.post(
        "/internal/ai/files",
        headers=auth_headers,
        files={"file": ("private.pdf", b"%PDF-private", "application/pdf")},
    )

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "FILE_UPLOAD_FAILED"
    assert error.error.category == "INTERNAL"
    assert error.error.retryable is True


@pytest.mark.parametrize(
    ("filename", "content", "content_type"),
    [
        ("private.ppt", b"%PDF-private", "application/pdf"),
        ("private.pdf", b"not-a-pdf", "application/pdf"),
        ("private.pdf", b"", "application/pdf"),
        ("private.pdf", b"%PDF-private", "application/octet-stream"),
    ],
)
async def test_upload_endpoint_rejects_unsupported_input_before_provider_call(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    fake_file_client: FakeXaiFileClient,
    filename: str,
    content: bytes,
    content_type: str,
) -> None:
    response = await client.post(
        "/internal/ai/files",
        headers=auth_headers,
        files={"file": (filename, content, content_type)},
    )

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "UNSUPPORTED_FORMAT"
    assert fake_file_client.uploads == []


async def test_upload_endpoint_enforces_provider_size_limit_before_call(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    fake_file_client: FakeXaiFileClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("edupilot_ai.api.files.XAI_FILE_MAX_BYTES", 12)

    response = await client.post(
        "/internal/ai/files",
        headers=auth_headers,
        files={"file": ("private.pdf", b"%PDF-12345678", "application/pdf")},
    )

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "FILE_TOO_LARGE"
    assert fake_file_client.uploads == []


async def test_delete_endpoint_is_idempotent_for_already_missing_file(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    fake_file_client: FakeXaiFileClient,
) -> None:
    response = await client.delete(
        "/internal/ai/files/file-already-missing",
        headers=auth_headers,
    )

    assert response.status_code == 204
    assert fake_file_client.deletes == ["file-already-missing"]


async def test_delete_endpoint_requires_internal_token(
    client: httpx.AsyncClient,
    fake_file_client: FakeXaiFileClient,
) -> None:
    response = await client.delete("/internal/ai/files/file-auth")

    assert response.status_code == 401
    assert InternalErrorResponse.model_validate(response.json()).error.category == "AUTH"
    assert fake_file_client.deletes == []


async def test_delete_endpoint_maps_provider_failure_to_retryable_502(
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    fake_file_client: FakeXaiFileClient,
) -> None:
    fake_file_client.delete_error = XaiFileClientError("FILE_DELETE_FAILED")

    response = await client.delete(
        "/internal/ai/files/file-delete-failure",
        headers=auth_headers,
    )

    assert response.status_code == 502
    error = InternalErrorResponse.model_validate(response.json())
    assert error.error.code == "FILE_DELETE_FAILED"
    assert error.error.category == "INTERNAL"
    assert error.error.retryable is True
    assert error.traceId == "contract-test-trace"
