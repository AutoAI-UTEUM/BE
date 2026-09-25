"""Private artifacts, immutable inputs and a cross-process provider request cap."""

import hashlib
import json
import os
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any

import httpx


class BenchmarkError(Exception):
    """Safe, static error code, never a provider response or validation input."""


def digest(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
    ).hexdigest()


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: Path, value: Any) -> None:
    # Artifacts are private and never overwrite an existing file (including a symlink).
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def make_output(path: Path, repositories: list[Path]) -> Path:
    resolved = path.resolve()
    if any(resolved.is_relative_to(repo.resolve()) for repo in repositories):
        raise BenchmarkError("OUTPUT_MUST_BE_OUTSIDE_REPOSITORIES")
    resolved.mkdir(mode=0o700, parents=True, exist_ok=False)
    return resolved


class RequestBudget:
    """Reserve before HTTP transport; retries and failed transmissions also consume slots."""

    def __init__(self, path: Path) -> None:
        self.path = path

    @classmethod
    def create(cls, path: Path, limit: int) -> RequestBudget:
        if limit < 1:
            raise BenchmarkError("POSITIVE_CALL_LIMIT_REQUIRED")
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(descriptor)
        with closing(sqlite3.connect(path)) as db, db:
            db.execute("CREATE TABLE budget (cap INTEGER NOT NULL, used INTEGER NOT NULL)")
            db.execute("INSERT INTO budget VALUES (?, 0)", (limit,))
        return cls(path)

    def reserve(self) -> None:
        with closing(sqlite3.connect(self.path, timeout=5)) as db, db:
            changed = db.execute("UPDATE budget SET used=used+1 WHERE used < cap").rowcount
            if changed != 1:
                raise BenchmarkError("PROVIDER_CALL_BUDGET_EXHAUSTED")

    def counts(self) -> tuple[int, int]:
        with closing(sqlite3.connect(self.path, timeout=5)) as db:
            cap, used = db.execute("SELECT cap, used FROM budget").fetchone()
        return int(cap), int(used)


class BudgetTransport(httpx.AsyncBaseTransport):
    """No unmetered redirects, Files calls or implicit HTTP-level retries."""

    def __init__(self, inner: httpx.AsyncBaseTransport, budget: RequestBudget) -> None:
        self.inner = inner
        self.budget = budget

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        if (
            request.method != "POST"
            or request.url.scheme != "https"
            or request.url.host != "api.x.ai"
            or request.url.port not in {None, 443}
            or request.url.path not in {"/v1/responses", "/v1/chat/completions"}
            or request.url.query
        ):
            raise BenchmarkError("UNEXPECTED_PROVIDER_ROUTE")
        self.budget.reserve()
        return await self.inner.handle_async_request(request)

    async def aclose(self) -> None:
        await self.inner.aclose()


class OfflineTransport(httpx.AsyncBaseTransport):
    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        raise BenchmarkError("LIVE_MODE_NOT_ENABLED")
