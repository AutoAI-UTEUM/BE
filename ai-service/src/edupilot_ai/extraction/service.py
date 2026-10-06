"""App-scoped admission and explicitly owned, terminable PDF parsing processes."""

import asyncio
import json
import logging
import os
import subprocess
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from http import HTTPStatus
from pathlib import Path
from typing import Any

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
logger = logging.getLogger(__name__)


def _worker_environment() -> dict[str, str]:
    # Windows asyncio needs the OS socket provider; service secrets remain excluded.
    if sys.platform == "win32":
        return {"SYSTEMROOT": os.environ.get("SYSTEMROOT", "C:\\Windows")}
    return {}


def extraction_timeout() -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.GATEWAY_TIMEOUT,
        code="EXTRACTION_TIMEOUT",
        category=ErrorCategory.TIMEOUT,
        message="PDF extraction exceeded its time budget.",
        retryable=False,
    )


def extraction_busy() -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.SERVICE_UNAVAILABLE,
        code="EXTRACTION_BUSY",
        category=ErrorCategory.INTERNAL,
        message="PDF extraction capacity is temporarily unavailable.",
        retryable=False,
    )


def _worker_failure() -> InternalApiError:
    return InternalApiError(
        status_code=HTTPStatus.INTERNAL_SERVER_ERROR,
        code="EXTRACTION_FAILED",
        category=ErrorCategory.INTERNAL,
        message="PDF extraction worker failed.",
        retryable=False,
    )


async def _reap(start: asyncio.Task[asyncio.subprocess.Process]) -> None:
    try:
        process = await start
    except Exception:
        return
    if process.returncode is None:
        try:
            if sys.platform == "win32":
                # venv python.exe is a launcher: killing only its PID leaves Python running.
                killer = await asyncio.create_subprocess_exec(
                    str(Path(_worker_environment()["SYSTEMROOT"]) / "System32" / "taskkill.exe"),
                    "/PID",
                    str(process.pid),
                    "/T",
                    "/F",
                    stdin=asyncio.subprocess.DEVNULL,
                    stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.DEVNULL,
                    creationflags=subprocess.CREATE_NO_WINDOW,
                    env=_worker_environment(),
                )
                if await killer.wait() != 0 and process.returncode is None:
                    raise RuntimeError("PDF worker termination was not confirmed")
            else:
                process.kill()
        except ProcessLookupError:
            pass
    # Drain without accumulating: a full PIPE can otherwise prevent wait() completing.
    if process.stdout is not None:
        while await process.stdout.read(64 * 1024):
            pass
    await process.wait()


class PdfExtractor:
    """Bound requests through staging/upload and retain ownership of late cleanup."""

    def __init__(
        self,
        *,
        max_concurrent: int = 2,
        max_waiting: int = 2,
        queue_timeout_seconds: float = 10,
        cleanup_timeout_seconds: float = 5,
    ) -> None:
        self._slots = asyncio.Semaphore(max_concurrent)
        self._capacity = max_concurrent + max_waiting
        self._queue_timeout = queue_timeout_seconds
        self._cleanup_timeout = cleanup_timeout_seconds
        self._admitted = 0
        self._requests: set[asyncio.Task[Any]] = set()
        self._cleanups: dict[asyncio.Task[None], Path | None] = {}
        self._deferred_paths: set[Path] = set()
        self._closed = False
        self._quarantined = False
        self._cleanup_failed = False

    @asynccontextmanager
    async def admit(self) -> AsyncIterator[None]:
        task = asyncio.current_task()
        if task is None:
            raise RuntimeError("PDF admission requires a running task")
        if task in self._requests:
            yield
            return
        if self._closed or self._quarantined or self._admitted >= self._capacity:
            raise extraction_busy()
        self._requests.add(task)
        self._admitted += 1
        try:
            yield
        finally:
            self._requests.discard(task)
            self._admitted -= 1

    async def extract(
        self,
        path: Path,
        *,
        max_pages: int,
        min_chars_per_page: int,
        min_meaningful_page_ratio: float,
        timeout_seconds: float,
    ) -> ExtractedDocument:
        async with self.admit():
            try:
                async with asyncio.timeout(min(self._queue_timeout, timeout_seconds)):
                    await self._slots.acquire()
            except TimeoutError:
                raise extraction_busy() from None
            try:
                if self._closed or self._quarantined:
                    raise extraction_busy()
                async with asyncio.timeout(timeout_seconds):
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
                raise extraction_timeout() from None
            finally:
                self._slots.release()

    def _track_cleanup(self, task: asyncio.Task[None], path: Path | None) -> None:
        self._cleanups[task] = path
        task.add_done_callback(self._cleanup_finished)

    def _cleanup_finished(self, task: asyncio.Task[None]) -> None:
        if task.cancelled() or task.exception() is not None:
            self._quarantined = True
            self._cleanup_failed = True
            logger.error("PDF cleanup failed", extra={"errorCode": "EXTRACTION_CLEANUP_FAILED"})
            return
        path = self._cleanups.pop(task)
        if path is not None and path in self._deferred_paths:
            self.discard_temporary(path)
        if not self._cleanups and not self._cleanup_failed:
            self._quarantined = False

    async def _cleanup(
        self, start: asyncio.Task[asyncio.subprocess.Process], path: Path | None
    ) -> None:
        task = asyncio.create_task(_reap(start))
        self._track_cleanup(task, path)
        deadline = asyncio.get_running_loop().time() + self._cleanup_timeout
        cancelled = False
        with CancelScope(shield=True):
            while not task.done():
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    self._quarantined = True
                    logger.error(
                        "PDF cleanup still pending",
                        extra={"errorCode": "EXTRACTION_CLEANUP_PENDING"},
                    )
                    break
                try:
                    await asyncio.wait({task}, timeout=remaining)
                except asyncio.CancelledError:
                    cancelled = True
            if task.done():
                task.result()
        if cancelled:
            raise asyncio.CancelledError

    def retain_read(self, path: Path, read: asyncio.Task[bytes]) -> None:
        """A cancelled request must not unlink a file still read by a thread."""

        async def drain() -> None:
            try:
                await asyncio.shield(read)
            except OSError:
                pass

        self._quarantined = True
        self._track_cleanup(asyncio.create_task(drain()), path)

    def discard_temporary(self, path: Path) -> None:
        if path in self._cleanups.values():
            self._deferred_paths.add(path)
            return
        try:
            path.unlink(missing_ok=True)
            self._deferred_paths.discard(path)
        except OSError:
            self._quarantined = True
            self._cleanup_failed = True
            logger.error(
                "PDF temporary cleanup failed", extra={"errorCode": "EXTRACTION_CLEANUP_FAILED"}
            )

    async def aclose(self) -> None:
        self._closed = True
        current = asyncio.current_task()
        for task in tuple(self._requests):
            if task is not current:
                task.cancel()
        deadline = asyncio.get_running_loop().time() + self._cleanup_timeout
        with CancelScope(shield=True):
            while pending := (self._requests - {current}) | set(self._cleanups):
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0:
                    logger.error(
                        "PDF shutdown cleanup still pending",
                        extra={"errorCode": "EXTRACTION_CLEANUP_PENDING"},
                    )
                    return
                await asyncio.wait(pending, timeout=remaining)

    async def _run(self, command: tuple[str, ...]) -> bytes:
        # Fixed executable/module; no shell, inherited API keys, .env, or parser stderr.
        creation_flags = 0
        if sys.platform == "win32":
            creation_flags = subprocess.CREATE_NO_WINDOW
        start = asyncio.create_task(
            asyncio.create_subprocess_exec(
                *command,
                stdin=asyncio.subprocess.DEVNULL,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                env=_worker_environment(),
                creationflags=creation_flags,
            )
        )
        try:
            process = await asyncio.shield(start)
            if process.stdout is None:
                raise _worker_failure()
            output = bytearray()
            while chunk := await process.stdout.read(64 * 1024):
                output.extend(chunk)
                if len(output) > _MAX_WORKER_OUTPUT_BYTES:
                    raise PdfExtractionError(PdfFailureReason.RESOURCE_LIMIT)
            if await process.wait() != 0:
                raise _worker_failure()
            return bytes(output)
        except OSError:
            raise _worker_failure() from None
        finally:
            path = Path(command[4]) if len(command) > 4 else None
            await self._cleanup(start, path)

    @staticmethod
    def _decode(output: bytes, *, max_pages: int) -> ExtractedDocument:
        try:
            result = json.loads(output)
            if result["status"] == "PAGE_LIMIT":
                raise PdfPageLimitError(page_count=int(result["page_count"]), max_pages=max_pages)
            if result["status"] == "FAILED":
                raise _worker_failure()
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
