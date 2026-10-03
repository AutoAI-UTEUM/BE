"""App-scoped admission and explicitly owned, terminable PDF parsing processes."""

import asyncio
import json
import sys
from http import HTTPStatus
from pathlib import Path

from anyio import CancelScope

from edupilot_ai.core.errors import ErrorCategory, InternalApiError
from edupilot_ai.extraction.pdf import (
    MAX_EXTRACTED_CHARS,
    ExtractedDocument,
    ExtractedPageData,
    PdfExtractionError,
    PdfFailureReason,
    PdfPageLimitError,
)

_MAX_WORKER_OUTPUT_BYTES = MAX_EXTRACTED_CHARS * 6 + 64 * 1024


async def _reap(start: asyncio.Task[asyncio.subprocess.Process]) -> None:
    try:
        process = await start
    except Exception:
        return
    if process.returncode is None:
        try:
            process.kill()
        except ProcessLookupError:
            pass
    # Drain without accumulating: a full PIPE can otherwise prevent wait() completing.
    if process.stdout is not None:
        while await process.stdout.read(64 * 1024):
            pass
    await process.wait()


async def _cleanup(start: asyncio.Task[asyncio.subprocess.Process]) -> None:
    """Reap even when cancelled during startup or repeatedly during cleanup."""
    task = asyncio.create_task(_reap(start))
    cancelled = False
    with CancelScope(shield=True):
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError:
                cancelled = True
        task.result()
    if cancelled:
        raise asyncio.CancelledError


class PdfExtractor:
    """Two parsers plus six waiters per app; queue time consumes the same budget."""

    def __init__(self) -> None:
        self._slots = asyncio.Semaphore(2)
        self._admitted = 0

    async def extract(
        self,
        path: Path,
        *,
        max_pages: int,
        min_chars_per_page: int,
        min_meaningful_page_ratio: float,
        timeout_seconds: float,
    ) -> ExtractedDocument:
        if self._admitted >= 8:
            raise InternalApiError(
                status_code=HTTPStatus.SERVICE_UNAVAILABLE,
                code="AI_SERVICE_UNAVAILABLE",
                category=ErrorCategory.INTERNAL,
                message="PDF extraction capacity is temporarily unavailable.",
                retryable=True,
            )
        self._admitted += 1
        try:
            async with asyncio.timeout(timeout_seconds), self._slots:
                output = await self._run(
                    (
                        sys.executable,
                        "-I",
                        "-m",
                        "edupilot_ai.extraction.worker",
                        str(path),
                        str(max_pages),
                        str(min_chars_per_page),
                        str(min_meaningful_page_ratio),
                        str(timeout_seconds),
                    )
                )
                return self._decode(output, max_pages=max_pages)
        except TimeoutError:
            raise InternalApiError(
                status_code=HTTPStatus.GATEWAY_TIMEOUT,
                code="AI_SERVICE_TIMEOUT",
                category=ErrorCategory.TIMEOUT,
                message="PDF extraction exceeded its time budget.",
                retryable=True,
            ) from None
        finally:
            self._admitted -= 1

    async def _run(self, command: tuple[str, ...]) -> bytes:
        # Fixed executable/module; no shell, inherited API keys, .env, or parser stderr.
        start = asyncio.create_task(
            asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                env={},
            )
        )
        try:
            process = await asyncio.shield(start)
            if process.stdout is None:
                raise PdfExtractionError(PdfFailureReason.RESOURCE_LIMIT)
            output = bytearray()
            while chunk := await process.stdout.read(64 * 1024):
                output.extend(chunk)
                if len(output) > _MAX_WORKER_OUTPUT_BYTES:
                    raise PdfExtractionError(PdfFailureReason.RESOURCE_LIMIT)
            if await process.wait() != 0:
                raise PdfExtractionError(PdfFailureReason.RESOURCE_LIMIT)
            return bytes(output)
        except OSError:
            raise PdfExtractionError(PdfFailureReason.RESOURCE_LIMIT) from None
        finally:
            await _cleanup(start)

    @staticmethod
    def _decode(output: bytes, *, max_pages: int) -> ExtractedDocument:
        try:
            result = json.loads(output)
            if result["status"] == "PAGE_LIMIT":
                raise PdfPageLimitError(page_count=int(result["page_count"]), max_pages=max_pages)
            if result["status"] != "OK":
                raise PdfExtractionError(PdfFailureReason(result["status"]))
            pages = tuple(ExtractedPageData(**item) for item in result["pages"])
            if not 1 <= len(pages) <= max_pages or any(
                page.page_number != index or not isinstance(page.text, str)
                for index, page in enumerate(pages, 1)
            ):
                raise ValueError
            if sum(len(page.text) for page in pages) > MAX_EXTRACTED_CHARS:
                raise ValueError
            return ExtractedDocument(pages=pages)
        except KeyError, TypeError, ValueError, UnicodeError:
            raise PdfExtractionError(PdfFailureReason.RESOURCE_LIMIT) from None
