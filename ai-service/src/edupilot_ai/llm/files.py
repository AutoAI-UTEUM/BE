"""xAI Files API client with safe failure classification and logging."""

import logging
from collections.abc import Awaitable
from time import perf_counter
from typing import Protocol
from urllib.parse import quote

import httpx
from pydantic import BaseModel, ConfigDict, Field, SecretStr, ValidationError

from edupilot_ai.llm.xai import XAI_BASE_URL

XAI_FILES_URL = f"{XAI_BASE_URL}/files"
XAI_FILE_MAX_BYTES = 48 * 1024 * 1024

logger = logging.getLogger(__name__)


class XaiFileClientError(Exception):
    """Safe provider failure that never carries a response body or file content."""

    def __init__(self, code: str, *, retryable: bool = True) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class XaiFileClientProtocol(Protocol):
    """Provider-neutral surface used by extract and file cleanup endpoints."""

    def upload(self, content: bytes, filename: str) -> Awaitable[str]: ...

    def delete(self, file_id: str) -> Awaitable[None]: ...


class _XaiFileResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(min_length=1)


class XaiFileClient:
    """Upload and delete PDF documents through the xAI Files API."""

    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        api_key: SecretStr,
        timeout_seconds: float,
    ) -> None:
        self._client = client
        self._api_key = api_key
        self._timeout_seconds = timeout_seconds

    async def upload(self, content: bytes, filename: str) -> str:
        started_at = perf_counter()
        size_bytes = len(content)
        if size_bytes > XAI_FILE_MAX_BYTES:
            self._log_upload_failure(started_at=started_at, size_bytes=size_bytes)
            raise XaiFileClientError("FILE_UPLOAD_FAILED", retryable=False)

        try:
            response = await self._client.post(
                XAI_FILES_URL,
                headers=self._headers(),
                data={"purpose": "assistants"},
                files={"file": (filename, content, "application/pdf")},
                timeout=httpx.Timeout(self._timeout_seconds),
            )
        except (httpx.TimeoutException, httpx.RequestError) as exception:
            self._log_upload_failure(started_at=started_at, size_bytes=size_bytes)
            raise XaiFileClientError("FILE_UPLOAD_FAILED") from exception

        if not response.is_success:
            self._log_upload_failure(started_at=started_at, size_bytes=size_bytes)
            raise XaiFileClientError(
                "FILE_UPLOAD_FAILED",
                retryable=response.status_code == httpx.codes.TOO_MANY_REQUESTS
                or response.status_code >= 500,
            )

        try:
            file_id = _XaiFileResponse.model_validate(response.json()).id.strip()
            if not file_id:
                raise ValueError("provider returned an empty file id")
        except (ValueError, ValidationError) as exception:
            self._log_upload_failure(started_at=started_at, size_bytes=size_bytes)
            raise XaiFileClientError(
                "FILE_UPLOAD_FAILED",
                retryable=False,
            ) from exception

        logger.info(
            "xAI file upload finished",
            extra={
                "tool": "files.upload",
                "status": "SUCCESS",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "fileId": file_id,
                "sizeBytes": size_bytes,
            },
        )
        return file_id

    async def delete(self, file_id: str) -> None:
        started_at = perf_counter()
        if not file_id.strip():
            raise XaiFileClientError("FILE_DELETE_FAILED", retryable=False)
        encoded_file_id = quote(file_id, safe="")
        try:
            response = await self._client.delete(
                f"{XAI_FILES_URL}/{encoded_file_id}",
                headers=self._headers(),
                timeout=httpx.Timeout(self._timeout_seconds),
            )
        except (httpx.TimeoutException, httpx.RequestError) as exception:
            self._log_delete_failure(started_at=started_at, file_id=file_id)
            raise XaiFileClientError("FILE_DELETE_FAILED") from exception

        if response.status_code == httpx.codes.NOT_FOUND:
            logger.info(
                "xAI file delete finished",
                extra={
                    "tool": "files.delete",
                    "status": "SUCCESS",
                    "durationMs": round((perf_counter() - started_at) * 1000, 3),
                    "fileId": file_id,
                },
            )
            return
        if not response.is_success:
            self._log_delete_failure(started_at=started_at, file_id=file_id)
            raise XaiFileClientError(
                "FILE_DELETE_FAILED",
                retryable=response.status_code == httpx.codes.TOO_MANY_REQUESTS
                or response.status_code >= 500,
            )

        logger.info(
            "xAI file delete finished",
            extra={
                "tool": "files.delete",
                "status": "SUCCESS",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "fileId": file_id,
            },
        )

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._api_key.get_secret_value()}"}

    @staticmethod
    def _log_upload_failure(*, started_at: float, size_bytes: int) -> None:
        logger.warning(
            "xAI file upload finished",
            extra={
                "tool": "files.upload",
                "status": "FAILED",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": "FILE_UPLOAD_FAILED",
                "sizeBytes": size_bytes,
            },
        )

    @staticmethod
    def _log_delete_failure(*, started_at: float, file_id: str) -> None:
        logger.warning(
            "xAI file delete finished",
            extra={
                "tool": "files.delete",
                "status": "FAILED",
                "durationMs": round((perf_counter() - started_at) * 1000, 3),
                "errorCode": "FILE_DELETE_FAILED",
                "fileId": file_id,
            },
        )
