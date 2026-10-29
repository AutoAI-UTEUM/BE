"""Offline regression tests for parser isolation, bounded work and private diagnostics."""

import asyncio
import contextlib
import ctypes
import logging
import os
import sys
from io import BytesIO
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, UploadFile
from pypdf import PdfReader

from edupilot_ai.api import extract as extract_api
from edupilot_ai.api.extract import _stage_upload
from edupilot_ai.core.errors import InternalApiError
from edupilot_ai.extraction.pdf import PdfExtractionError, PdfFailureReason, extract_pdf
from edupilot_ai.extraction.service import PdfExtractor
from edupilot_ai.settings import Settings
from tests.fakes import FakeXaiFileClient
from tests.pdf_factory import make_pdf

_TEXT = "This is a normal lecture with enough text to preserve all existing extraction behavior."
_SLEEP = (
    "import os,sys,time;from pathlib import Path;"
    "Path(sys.argv[1]+'.pid').write_text(str(os.getpid()));time.sleep(30)"
)


class ScriptedExtractor(PdfExtractor):
    def __init__(self, script: str = _SLEEP) -> None:
        super().__init__()
        self.script = script

    async def _run(self, command: tuple[str, ...]) -> bytes:
        return await super()._run((sys.executable, "-I", "-c", self.script, command[4]))


async def run_extract(extractor: PdfExtractor, path: Path, budget: float = 10.0) -> None:
    await extractor.extract(
        path,
        max_pages=300,
        min_chars_per_page=50,
        min_meaningful_page_ratio=0.05,
        timeout_seconds=budget,
    )


async def wait_pid(path: Path) -> int:
    async with asyncio.timeout(3):
        # An isolated child cannot signal an in-process asyncio.Event.
        while not path.with_suffix(path.suffix + ".pid").exists():  # noqa: ASYNC110
            await asyncio.sleep(0.01)
    return int(path.with_suffix(path.suffix + ".pid").read_text())


def assert_reaped(pid: int) -> None:
    if sys.platform == "win32":
        from ctypes import wintypes

        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x00100000, False, pid)
        if not handle:
            assert ctypes.get_last_error() == 87
            return
        try:
            assert kernel.WaitForSingleObject(handle, 0) == 0
        finally:
            kernel.CloseHandle(handle)
        return
    with pytest.raises(ProcessLookupError):
        os.kill(pid, 0)


@pytest.mark.parametrize("extra", [0, 1])
def test_total_text_limit_rejects_instead_of_truncating(tmp_path: Path, extra: int) -> None:
    path = tmp_path / "aggregate.pdf"
    path.write_bytes(make_pdf("a" * 50, "b" * (50 + extra)))
    if extra:
        with pytest.raises(PdfExtractionError) as caught:
            extract_pdf(
                path,
                max_pages=300,
                min_chars_per_page=50,
                min_meaningful_page_ratio=0.05,
                max_text_chars=100,
            )
        assert caught.value.reason == PdfFailureReason.RESOURCE_LIMIT
    else:
        document = extract_pdf(
            path,
            max_pages=300,
            min_chars_per_page=50,
            min_meaningful_page_ratio=0.05,
            max_text_chars=100,
        )
        assert [page.text for page in document.pages] == ["a" * 50, "b" * 50]


@pytest.mark.parametrize(
    "cmap",
    [
        b"1 beginbfchar\n<41> <PRIVATE-CMAP-MARKER>\nendbfchar\n",
        b"1 beginbfrange\n<41> <42> <PRIVATE-CMAP-MARKER>\nendbfrange\n",
    ],
)
def test_real_malformed_cmap_logs_never_escape_shared_core(
    tmp_path: Path,
    cmap: bytes,
    caplog: pytest.LogCaptureFixture,
    capfd: pytest.CaptureFixture[str],
) -> None:
    path = tmp_path / "PRIVATE-NAME.pdf"
    path.write_bytes(make_pdf(_TEXT, cmap=cmap))
    child = logging.getLogger("pypdf._cmap")
    original = (child.disabled, child.level, tuple(child.handlers))
    child.addHandler(caplog.handler)
    try:
        # Positive sink control: the unguarded dependency really emits the marker.
        with contextlib.suppress(Exception):
            PdfReader(path).pages[0].extract_text()
        assert "PRIVATE-CMAP-MARKER" in caplog.text
        caplog.clear()
        capfd.readouterr()
        with contextlib.suppress(PdfExtractionError):
            extract_pdf(path, max_pages=300, min_chars_per_page=50, min_meaningful_page_ratio=0.05)
        assert "PRIVATE" not in caplog.text
        captured = capfd.readouterr()
        assert "PRIVATE" not in captured.out + captured.err
    finally:
        child.removeHandler(caplog.handler)
    assert (child.disabled, child.level, tuple(child.handlers)) == original


async def test_timeout_reaps_child_and_next_document_succeeds(tmp_path: Path) -> None:
    path = tmp_path / "slow.pdf"
    path.write_bytes(make_pdf(_TEXT))
    task = asyncio.create_task(run_extract(ScriptedExtractor(), path, 0.3))
    pid = await wait_pid(path)
    with pytest.raises(InternalApiError) as caught:
        await task
    assert caught.value.code == "EXTRACTION_TIMEOUT"
    assert_reaped(pid)
    await run_extract(PdfExtractor(), path)


async def test_cancel_reaps_child_and_releases_admission(tmp_path: Path) -> None:
    path = tmp_path / "cancel.pdf"
    extractor = ScriptedExtractor()
    task = asyncio.create_task(run_extract(extractor, path))
    pid = await wait_pid(path)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert_reaped(pid)
    assert extractor._admitted == 0


async def test_cancel_during_startup_still_owns_and_reaps_child(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    spawned = asyncio.Event()
    created: list[asyncio.subprocess.Process] = []
    original = asyncio.create_subprocess_exec

    async def delayed(*args: Any, **kwargs: Any) -> asyncio.subprocess.Process:
        process = await original(*args, **kwargs)
        created.append(process)
        spawned.set()
        await asyncio.sleep(0.1)
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", delayed)
    task = asyncio.create_task(run_extract(ScriptedExtractor(), tmp_path / "startup.pdf"))
    await spawned.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert_reaped(created[0].pid)


async def test_worker_output_is_bounded_and_full_pipe_does_not_block_cleanup(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from edupilot_ai.extraction import service

    monkeypatch.setattr(service, "_MAX_WORKER_OUTPUT_BYTES", 128)
    script = _SLEEP.replace("time.sleep(30)", "sys.stdout.write('x'*1000000);time.sleep(30)")
    path = tmp_path / "overflow.pdf"
    with pytest.raises(PdfExtractionError) as caught:
        await run_extract(ScriptedExtractor(script), path)
    assert caught.value.reason == PdfFailureReason.RESOURCE_LIMIT
    assert_reaped(await wait_pid(path))


async def test_admission_bounds_two_active_and_two_waiters(tmp_path: Path) -> None:
    extractor = ScriptedExtractor()
    tasks = [
        asyncio.create_task(run_extract(extractor, tmp_path / f"{i}.pdf", 5)) for i in range(4)
    ]
    try:
        pids = [await wait_pid(tmp_path / f"{i}.pdf") for i in range(2)]
        assert not (tmp_path / "2.pdf.pid").exists()
        with pytest.raises(InternalApiError) as caught:
            await run_extract(extractor, tmp_path / "rejected.pdf")
        assert caught.value.code == "EXTRACTION_BUSY"
        assert not caught.value.retryable
        assert not (tmp_path / "rejected.pdf.pid").exists()
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    assert extractor._admitted == 0
    for pid in pids:
        assert_reaped(pid)


@pytest.mark.skipif(
    sys.platform != "linux", reason="Production RLIMIT_AS enforcement is Linux-only"
)
async def test_linux_memory_limit_blocks_allocation_in_real_child() -> None:
    output = await PdfExtractor()._run(
        (
            sys.executable,
            "-I",
            "-c",
            "from edupilot_ai.extraction.worker import set_resource_limits,MEMORY_LIMIT_BYTES\n"
            "set_resource_limits(2)\n"
            "try: bytearray(MEMORY_LIMIT_BYTES*2)\n"
            "except MemoryError: print('MEMORY_LIMIT_ENFORCED')\n",
        )
    )
    assert output.strip() == b"MEMORY_LIMIT_ENFORCED"


async def test_child_has_no_service_secrets(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("XAI_API_KEY", "PRIVATE-KEY-MARKER")
    output = await PdfExtractor()._run(
        (
            sys.executable,
            "-I",
            "-c",
            "import os;print('EMPTY' if 'XAI_API_KEY' not in os.environ else 'LEAK')",
        )
    )
    assert output.strip() == b"EMPTY"


async def test_queue_wait_consumes_budget_without_starting_another_child(tmp_path: Path) -> None:
    extractor = ScriptedExtractor()
    active = [
        asyncio.create_task(run_extract(extractor, tmp_path / f"active{i}.pdf", 5))
        for i in range(2)
    ]
    try:
        pids = [await wait_pid(tmp_path / f"active{i}.pdf") for i in range(2)]
        with pytest.raises(InternalApiError) as caught:
            await run_extract(extractor, tmp_path / "queued.pdf", 0.05)
        assert caught.value.code == "EXTRACTION_BUSY"
        assert not (tmp_path / "queued.pdf.pid").exists()
        assert extractor._admitted == 2
    finally:
        for task in active:
            task.cancel()
        await asyncio.gather(*active, return_exceptions=True)
    for pid in pids:
        assert_reaped(pid)


async def test_two_normal_uploads_are_not_rejected(tmp_path: Path) -> None:
    path = tmp_path / "normal.pdf"
    path.write_bytes(make_pdf(_TEXT))
    extractor = PdfExtractor()
    await asyncio.gather(run_extract(extractor, path), run_extract(extractor, path))


async def test_slow_extract_keeps_health_responsive_and_never_uploads(
    app: FastAPI,
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
) -> None:
    app.state.pdf_extractor = ScriptedExtractor("import time;time.sleep(30)")
    settings.extract_timeout_seconds = 1
    settings.edupilot_xai_files_enabled = True
    request = asyncio.create_task(
        client.post(
            "/internal/ai/extract",
            headers=auth_headers,
            files={"file": ("normal.pdf", make_pdf(_TEXT), "application/pdf")},
        )
    )
    await asyncio.sleep(0.1)
    async with asyncio.timeout(0.5):
        assert (await client.get("/health")).status_code == 200
    response = await request
    assert response.status_code == 504
    assert response.json()["error"]["code"] == "EXTRACTION_TIMEOUT"
    assert fake_file_client.uploads == []


async def test_staging_cancellation_removes_named_temporary(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    created: list[Path] = []
    reading = asyncio.Event()

    def temporary(**kwargs: Any) -> Any:
        result = NamedTemporaryFile(dir=tmp_path, **kwargs)
        created.append(Path(result.name))
        return result

    class PausedUpload(UploadFile):
        async def read(self, size: int = -1) -> bytes:
            reading.set()
            await asyncio.sleep(30)
            return b""

    monkeypatch.setattr(extract_api, "NamedTemporaryFile", temporary)
    task = asyncio.create_task(_stage_upload(PausedUpload(BytesIO(b"%PDF-")), max_bytes=100))
    await reading.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert created and all(not path.exists() for path in created)


async def test_parser_crash_returns_safe_existing_error(
    app: FastAPI,
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    capfd: pytest.CaptureFixture[str],
) -> None:
    app.state.pdf_extractor = ScriptedExtractor(
        "import sys;sys.stderr.write('PRIVATE-PARSER-FRAGMENT');sys.exit(1)"
    )
    response = await client.post(
        "/internal/ai/extract",
        headers=auth_headers,
        files={"file": ("PRIVATE-NAME.pdf", make_pdf(_TEXT), "application/pdf")},
    )
    assert response.status_code == 500
    assert response.json()["error"] == {
        "code": "EXTRACTION_FAILED",
        "category": "INTERNAL",
        "message": "PDF extraction worker failed.",
        "retryable": False,
    }
    captured = capfd.readouterr()
    assert "PRIVATE" not in captured.out + captured.err + response.text
