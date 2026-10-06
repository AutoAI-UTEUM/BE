"""Real worker lifecycle and HTTP disconnect regressions for the extraction boundary."""

import asyncio
import json
import socket
import sys
from pathlib import Path
from typing import Any

import httpx
import pytest
import uvicorn
from fastapi import FastAPI

from edupilot_ai.core.errors import InternalApiError
from edupilot_ai.extraction.service import PdfExtractor
from edupilot_ai.settings import Settings
from tests.fakes import FakeXaiFileClient
from tests.pdf_factory import make_pdf
from tests.test_pdf_security import ScriptedExtractor, assert_reaped, run_extract, wait_pid

_TEXT = "Synthetic lecture content that preserves normal text extraction behavior."


async def test_shutdown_cancels_active_and_queued_jobs_and_stops_admission(tmp_path: Path) -> None:
    extractor = ScriptedExtractor()
    tasks = [
        asyncio.create_task(run_extract(extractor, tmp_path / f"shutdown-{i}.pdf", 10))
        for i in range(4)
    ]
    pids = [await wait_pid(tmp_path / f"shutdown-{i}.pdf") for i in range(2)]
    await extractor.aclose()
    results = await asyncio.gather(*tasks, return_exceptions=True)
    assert all(isinstance(result, asyncio.CancelledError) for result in results)
    assert extractor._admitted == 0
    assert not extractor._cleanups
    for pid in pids:
        assert_reaped(pid)
    with pytest.raises(InternalApiError) as caught:
        await run_extract(extractor, tmp_path / "after-close.pdf")
    assert caught.value.code == "EXTRACTION_BUSY"
    assert not caught.value.retryable


async def test_late_startup_keeps_cleanup_and_file_owned_until_reaped(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    release = asyncio.Event()
    original = asyncio.create_subprocess_exec

    async def delayed(*args: Any, **kwargs: Any) -> asyncio.subprocess.Process:
        process = await original(*args, **kwargs)
        if args[0] == sys.executable:
            await release.wait()
        return process

    monkeypatch.setattr(asyncio, "create_subprocess_exec", delayed)
    extractor = ScriptedExtractor()
    extractor._cleanup_timeout = 0.05
    path = tmp_path / "late.pdf"
    path.write_bytes(b"synthetic temporary data")
    task = asyncio.create_task(run_extract(extractor, path, 10))
    pid = await wait_pid(path)
    try:
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 0.5)
        extractor.discard_temporary(path)
        assert path.exists()
        assert extractor._quarantined
        with pytest.raises(InternalApiError):
            await run_extract(extractor, tmp_path / "not-admitted.pdf")
        release.set()
        await asyncio.wait_for(asyncio.gather(*tuple(extractor._cleanups)), 3)
        await asyncio.sleep(0)
        assert_reaped(pid)
        assert not path.exists()
        assert not extractor._cleanups
        assert not extractor._quarantined
    finally:
        release.set()
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        await extractor.aclose()


@pytest.mark.parametrize("unicode_hex, expected", [("AC00", "가"), ("D83DDE00", "😀")])
async def test_actual_worker_preserves_unicode_page_text(
    tmp_path: Path, unicode_hex: str, expected: str
) -> None:
    path = tmp_path / "unicode.pdf"
    path.write_bytes(
        make_pdf("A" * 60, cmap=f"1 beginbfchar\n<41> <{unicode_hex}>\nendbfchar\n".encode("ascii"))
    )
    document = await PdfExtractor().extract(
        path,
        max_pages=300,
        min_chars_per_page=50,
        min_meaningful_page_ratio=0.05,
        timeout_seconds=10,
    )
    assert document.pages[0].text == expected * 60


async def test_rejected_request_never_stages_named_temporary(
    app: FastAPI,
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from edupilot_ai.api import extract as extract_api

    extractor = ScriptedExtractor()
    app.state.pdf_extractor = extractor
    tasks = [
        asyncio.create_task(run_extract(extractor, tmp_path / f"capacity-{i}.pdf", 10))
        for i in range(4)
    ]
    staged: list[object] = []

    def forbidden_staging(**kwargs: Any) -> Any:
        staged.append(kwargs)
        raise AssertionError("Rejected requests must not stage a named file")

    monkeypatch.setattr(extract_api, "NamedTemporaryFile", forbidden_staging)
    try:
        await wait_pid(tmp_path / "capacity-0.pdf")
        await wait_pid(tmp_path / "capacity-1.pdf")
        response = await client.post(
            "/internal/ai/extract",
            headers=auth_headers,
            files={"file": ("synthetic.pdf", make_pdf(_TEXT), "application/pdf")},
        )
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "EXTRACTION_BUSY"
        assert response.json()["error"]["retryable"] is False
        assert staged == []
    finally:
        await extractor.aclose()
        await asyncio.gather(*tasks, return_exceptions=True)


async def test_queue_has_its_own_short_deadline_without_starting_worker(tmp_path: Path) -> None:
    extractor = ScriptedExtractor()
    extractor._queue_timeout = 0.05
    tasks = [
        asyncio.create_task(run_extract(extractor, tmp_path / f"running-{i}.pdf", 10))
        for i in range(2)
    ]
    try:
        await wait_pid(tmp_path / "running-0.pdf")
        await wait_pid(tmp_path / "running-1.pdf")
        async with asyncio.timeout(0.5):
            with pytest.raises(InternalApiError) as caught:
                await run_extract(extractor, tmp_path / "waiting.pdf", 10)
        assert caught.value.code == "EXTRACTION_BUSY"
        assert not (tmp_path / "waiting.pdf.pid").exists()
    finally:
        await extractor.aclose()
        await asyncio.gather(*tasks, return_exceptions=True)


async def test_total_deadline_during_upload_preserves_completed_text(
    app: FastAPI,
    client: httpx.AsyncClient,
    auth_headers: dict[str, str],
    settings: Settings,
    fake_file_client: FakeXaiFileClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class CompletedExtractor(PdfExtractor):
        async def _run(self, command: tuple[str, ...]) -> bytes:
            await asyncio.sleep(0.6)
            return json.dumps(
                {"status": "OK", "pages": [{"page_number": 1, "text": _TEXT}]}
            ).encode()

    cancelled = asyncio.Event()

    async def slow_upload(content: bytes, filename: str) -> str:
        try:
            await asyncio.sleep(30)
            return "never-returned"
        finally:
            cancelled.set()

    app.state.pdf_extractor = CompletedExtractor()
    monkeypatch.setattr(fake_file_client, "upload", slow_upload)
    settings.edupilot_xai_files_enabled = True
    settings.extract_total_timeout_seconds = 1
    async with asyncio.timeout(2):
        response = await client.post(
            "/internal/ai/extract",
            headers=auth_headers,
            files={"file": ("synthetic.pdf", make_pdf(_TEXT), "application/pdf")},
        )
    assert response.status_code == 200
    assert response.json()["pages"][0]["text"] == _TEXT
    assert response.json()["xaiFileId"] is None
    assert response.json()["warnings"][0]["type"] == "FILE_UPLOAD_FAILED"
    assert cancelled.is_set()


async def test_real_http_disconnect_reaps_worker_and_removes_temporary(
    app: FastAPI,
    auth_headers: dict[str, str],
) -> None:
    async def fast_ai_stub() -> dict[str, str]:
        return {"result": "synthetic-fast-response"}

    app.add_api_route("/internal/ai/synthetic-fast", fast_ai_stub, methods=["POST"])

    class ObservedExtractor(ScriptedExtractor):
        def __init__(self) -> None:
            super().__init__()
            self.paths: asyncio.Queue[Path] = asyncio.Queue()

        async def _run(self, command: tuple[str, ...]) -> bytes:
            self.paths.put_nowait(Path(command[4]))
            return await super()._run(command)

    extractor = ObservedExtractor()
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    server = uvicorn.Server(
        uvicorn.Config(
            app, log_config=None, log_level="warning", lifespan="on", timeout_graceful_shutdown=2
        )
    )
    serving = asyncio.create_task(server.serve(sockets=[listener]))
    request: asyncio.Task[httpx.Response] | None = None
    try:
        async with asyncio.timeout(5):
            # Uvicorn exposes only the started flag, not a startup event.
            while not server.started:  # noqa: ASYNC110
                await asyncio.sleep(0.01)
        app.state.pdf_extractor = extractor
        port = listener.getsockname()[1]
        async with httpx.AsyncClient(base_url=f"http://127.0.0.1:{port}") as network:
            request = asyncio.create_task(
                network.post(
                    "/internal/ai/extract",
                    headers=auth_headers,
                    files={"file": ("synthetic.pdf", make_pdf(_TEXT), "application/pdf")},
                )
            )
            path = await asyncio.wait_for(extractor.paths.get(), 5)
            pid = await wait_pid(path)
            async with asyncio.timeout(0.5):
                assert (await network.get("/health")).status_code == 200
                fast = await network.post("/internal/ai/synthetic-fast", headers=auth_headers)
                assert fast.json() == {"result": "synthetic-fast-response"}
            request.cancel()
            with pytest.raises(asyncio.CancelledError):
                await request
            async with asyncio.timeout(7):
                # Observe handler ownership after a real socket disconnect.
                while extractor._admitted:  # noqa: ASYNC110
                    await asyncio.sleep(0.01)
            assert_reaped(pid)
            assert not path.exists()
    finally:
        if request is not None:
            request.cancel()
            await asyncio.gather(request, return_exceptions=True)
        await extractor.aclose()
        server.should_exit = True
        await asyncio.wait_for(serving, 5)
        listener.close()


@pytest.mark.skipif(sys.platform != "linux", reason="Production CPU limit is Linux-only")
async def test_linux_cpu_budget_terminates_actual_worker() -> None:
    async with asyncio.timeout(5):
        with pytest.raises(InternalApiError) as caught:
            await PdfExtractor()._run(
                (
                    sys.executable,
                    "-I",
                    "-c",
                    "from edupilot_ai.extraction.worker import set_resource_limits\n"
                    "set_resource_limits(0.1)\nwhile True: pass\n",
                )
            )
    assert caught.value.code == "EXTRACTION_FAILED"


@pytest.mark.skipif(sys.platform != "linux", reason="Linux RSS measurement")
async def test_four_synthetic_documents_record_process_memory_and_duration(tmp_path: Path) -> None:
    script = (
        "import json,os,sys,time;from pathlib import Path;"
        "from dataclasses import asdict;"
        "from edupilot_ai.extraction.worker import set_resource_limits;"
        "from edupilot_ai.extraction.pdf import extract_pdf;"
        "set_resource_limits(10);path=Path(sys.argv[1]);started=time.perf_counter();"
        "document=extract_pdf(path,max_pages=300,min_chars_per_page=50,"
        "min_meaningful_page_ratio=0.05);"
        "rss=int(next(line for line in Path('/proc/self/status').read_text().splitlines() "
        "if line.startswith('VmHWM:')).split()[1]);"
        "path.with_suffix('.stats.json').write_text(json.dumps({'pid':os.getpid(),"
        "'parse_ms':round((time.perf_counter()-started)*1000,2),'peak_rss_kb':rss}));"
        "print(json.dumps({'status':'OK','pages':asdict(document)['pages']}))"
    )

    class MeasuredExtractor(PdfExtractor):
        async def _run(self, command: tuple[str, ...]) -> bytes:
            return await super()._run((sys.executable, "-I", "-c", script, command[4]))

    paths = []
    for index in range(4):
        path = tmp_path / f"measured-{index}.pdf"
        path.write_bytes(make_pdf(*([_TEXT * 30] * (20 if index % 2 else 1))))
        paths.append(path)
    extractor = MeasuredExtractor()
    started = asyncio.get_running_loop().time()
    try:
        async with asyncio.timeout(30):
            results = await asyncio.gather(
                *(
                    extractor.extract(
                        path,
                        max_pages=300,
                        min_chars_per_page=50,
                        min_meaningful_page_ratio=0.05,
                        timeout_seconds=10,
                    )
                    for path in paths
                )
            )
        assert [result.page_count for result in results] == [1, 20, 1, 20]
        measurements = [json.loads(path.with_suffix(".stats.json").read_text()) for path in paths]
        for measurement in measurements:
            assert 0 < measurement["peak_rss_kb"] < 512 * 1024
            assert_reaped(measurement["pid"])
        parent_status = await asyncio.to_thread(Path("/proc/self/status").read_text)
        parent_peak = int(
            next(line for line in parent_status.splitlines() if line.startswith("VmHWM:")).split()[
                1
            ]
        )
        print(
            json.dumps(
                {
                    "synthetic": True,
                    "active_limit": 2,
                    "admitted_limit": 4,
                    "elapsed_ms": round((asyncio.get_running_loop().time() - started) * 1000, 2),
                    "parent_test_process_peak_rss_kb": parent_peak,
                    "workers": measurements,
                }
            )
        )
    finally:
        await extractor.aclose()


async def test_one_timeout_does_not_terminate_other_worker(tmp_path: Path) -> None:
    script = (
        "import json,os,sys,time;from pathlib import Path;"
        "path=Path(sys.argv[1]);path.with_suffix('.pdf.pid').write_text(str(os.getpid()));"
        "time.sleep(30 if path.stem=='timeout' else 4);"
        "print(json.dumps({'status':'OK','pages':[{'page_number':1,'text':'" + _TEXT + "'}]}))"
    )
    extractor = ScriptedExtractor(script)
    first = asyncio.create_task(run_extract(extractor, tmp_path / "timeout.pdf", 3))
    second = asyncio.create_task(run_extract(extractor, tmp_path / "normal.pdf", 10))
    try:
        a = await wait_pid(tmp_path / "timeout.pdf")
        b = await wait_pid(tmp_path / "normal.pdf")
        with pytest.raises(InternalApiError) as caught:
            await first
        assert caught.value.code == "EXTRACTION_TIMEOUT"
        assert_reaped(a)
        await second
        assert_reaped(b)
        assert extractor._admitted == 0 and not extractor._cleanups
    finally:
        first.cancel()
        second.cancel()
        await asyncio.gather(first, second, return_exceptions=True)
        await extractor.aclose()


async def test_cancelled_file_read_retains_temporary_until_thread_finishes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import threading

    from edupilot_ai.api.extract import _upload_original_pdf

    path = tmp_path / "reading.pdf"
    path.write_bytes(make_pdf(_TEXT))
    release = threading.Event()
    original = Path.read_bytes

    def delayed_read(self: Path) -> bytes:
        if self == path and not release.wait(2):
            raise OSError("Synthetic read barrier timed out")
        return original(self)

    monkeypatch.setattr(Path, "read_bytes", delayed_read)
    extractor = PdfExtractor()
    file_client = FakeXaiFileClient()
    try:
        _, warnings = await _upload_original_pdf(
            path=path,
            filename="synthetic.pdf",
            size_bytes=path.stat().st_size,
            file_client=file_client,
            extractor=extractor,
            timeout_seconds=0.05,
        )
        assert warnings[0].type == "FILE_UPLOAD_FAILED"
        assert file_client.uploads == []
        extractor.discard_temporary(path)
        assert path.exists() and extractor._quarantined
        release.set()
        await asyncio.wait_for(asyncio.gather(*tuple(extractor._cleanups)), 3)
        await asyncio.sleep(0)
        assert not path.exists() and not extractor._quarantined
    finally:
        release.set()
        await extractor.aclose()


async def test_repeated_success_crash_and_cancel_do_not_accumulate_capacity(tmp_path: Path) -> None:
    script = (
        "import json,os,sys,time;from pathlib import Path;"
        "path=Path(sys.argv[1]);path.with_suffix('.pdf.pid').write_text(str(os.getpid()));"
        "time.sleep(30 if 'cancel' in path.stem else 0.01);"
        "sys.exit(1) if 'crash' in path.stem else None;"
        "print(json.dumps({'status':'OK','pages':[{'page_number':1,'text':'" + _TEXT + "'}]}))"
    )
    extractor = ScriptedExtractor(script)
    try:
        for index in range(2):
            normal = tmp_path / f"normal-{index}.pdf"
            await run_extract(extractor, normal, 10)
            assert_reaped(await wait_pid(normal))
            crash = tmp_path / f"crash-{index}.pdf"
            with pytest.raises(InternalApiError):
                await run_extract(extractor, crash, 10)
            assert_reaped(await wait_pid(crash))
            cancel = tmp_path / f"cancel-{index}.pdf"
            task = asyncio.create_task(run_extract(extractor, cancel, 10))
            pid = await wait_pid(cancel)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert_reaped(pid)
            assert extractor._admitted == 0 and not extractor._cleanups
            assert not extractor._requests and not extractor._deferred_paths
    finally:
        await extractor.aclose()


async def test_actual_45mib_staging_boundary_is_checked_and_cleaned(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from io import BytesIO
    from tempfile import NamedTemporaryFile

    from fastapi import UploadFile

    from edupilot_ai.api import extract as extract_api

    created: list[Path] = []

    def temporary(**kwargs: Any) -> Any:
        result = NamedTemporaryFile(dir=tmp_path, **kwargs)
        created.append(Path(result.name))
        return result

    monkeypatch.setattr(extract_api, "NamedTemporaryFile", temporary)
    limit = 45 * 1024 * 1024
    for extra in (0, 1):
        upload = UploadFile(BytesIO(b"%PDF-" + bytes(limit + extra - 5)))
        try:
            if extra:
                with pytest.raises(InternalApiError) as caught:
                    await extract_api._stage_upload(upload, max_bytes=limit)
                assert caught.value.code == "FILE_TOO_LARGE"
            else:
                path = await extract_api._stage_upload(upload, max_bytes=limit)
                assert path.stat().st_size == limit
                path.unlink()
        finally:
            await upload.close()
    assert all(not path.exists() for path in created)
