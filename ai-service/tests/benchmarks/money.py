"""Offline-tested monetary reservations; no credential loading or network entry point."""

import os
import sqlite3
from collections.abc import Callable
from contextlib import closing
from pathlib import Path

import httpx

from tests.benchmarks.common import BenchmarkError

USD_TICKS_PER_DOLLAR = 10_000_000_000
MAX_APPROVED_COST_TICKS = 5 * USD_TICKS_PER_DOLLAR


class MonetaryBudget:
    """Reserve worst-case costs permanently, including retries and unknown outcomes.

    Units are provider USD ticks (1 USD = 10**10 ticks), never floating point USD.
    The caller must prove provider rates and billable token bounds before use.
    Actual usage is recorded separately and never replenishes reserved capacity.
    """

    def __init__(self, path: Path) -> None:
        self.path = path

    @classmethod
    def create(cls, path: Path, *, call_cap: int, cost_cap: int) -> MonetaryBudget:
        if type(call_cap) is not int or not 1 <= call_cap <= 20:
            raise BenchmarkError("CALL_CAP_MUST_BE_BETWEEN_1_AND_20")
        if type(cost_cap) is not int or not 1 <= cost_cap <= MAX_APPROVED_COST_TICKS:
            raise BenchmarkError("COST_CAP_MUST_BE_POSITIVE_AND_AT_MOST_FIVE_USD")
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(descriptor)
        with closing(sqlite3.connect(path)) as db, db:
            db.execute(
                "CREATE TABLE budget (call_cap INTEGER, cost_cap INTEGER, "
                "used INTEGER, reserved INTEGER, blocked INTEGER)"
            )
            db.execute("INSERT INTO budget VALUES (?, ?, 0, 0, 0)", (call_cap, cost_cap))
            db.execute(
                "CREATE TABLE attempts (id INTEGER PRIMARY KEY, upper_bound INTEGER, "
                "actual INTEGER, reconciled INTEGER NOT NULL DEFAULT 0)"
            )
        return cls(path)

    def reserve(self, upper_bound: int) -> int:
        if type(upper_bound) is not int or not 1 <= upper_bound <= 2**63 - 1:
            raise BenchmarkError("VERIFIED_POSITIVE_COST_BOUND_REQUIRED")
        with closing(sqlite3.connect(self.path, timeout=5)) as db, db:
            changed = db.execute(
                "UPDATE budget SET used=used+1, reserved=reserved+? "
                "WHERE blocked=0 AND used<call_cap AND ?<=cost_cap-reserved",
                (upper_bound, upper_bound),
            ).rowcount
            if changed != 1:
                raise BenchmarkError("MONETARY_OR_CALL_BUDGET_EXHAUSTED")
            cursor = db.execute("INSERT INTO attempts (upper_bound) VALUES (?)", (upper_bound,))
            assert cursor.lastrowid is not None
            return cursor.lastrowid

    def record_actual(self, attempt_id: int, actual: int | None) -> None:
        if type(attempt_id) is not int or attempt_id < 1:
            raise BenchmarkError("UNKNOWN_ATTEMPT")
        if actual is not None and (type(actual) is not int or not 0 <= actual <= 2**63 - 1):
            raise BenchmarkError("INVALID_ACTUAL_COST")
        exceeded = False
        with closing(sqlite3.connect(self.path, timeout=5)) as db, db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT upper_bound, reconciled FROM attempts WHERE id=?", (attempt_id,)
            ).fetchone()
            if row is None:
                raise BenchmarkError("UNKNOWN_ATTEMPT")
            if row[1]:
                raise BenchmarkError("ATTEMPT_ALREADY_RECONCILED")
            db.execute(
                "UPDATE attempts SET actual=?, reconciled=1 WHERE id=?", (actual, attempt_id)
            )
            if actual is not None and actual > row[0]:
                db.execute("UPDATE budget SET blocked=1")
                exceeded = True
        if exceeded:
            raise BenchmarkError("PROVIDER_COST_EXCEEDED_VERIFIED_BOUND")

    def summary(self) -> dict[str, int | None]:
        with closing(sqlite3.connect(self.path, timeout=5)) as db:
            db.execute("BEGIN")
            cap, cost_cap, used, reserved, blocked = db.execute("SELECT * FROM budget").fetchone()
            actuals = [row[0] for row in db.execute("SELECT actual FROM attempts")]
            known = sum(actual for actual in actuals if actual is not None)
            unknown = sum(actual is None for actual in actuals)
        return {
            "callCap": cap,
            "costCap": cost_cap,
            "callsReserved": used,
            "costReserved": reserved,
            "knownActualCost": known,
            "unknownAttempts": unknown,
            "totalActualCost": None if unknown else known,
            "blocked": blocked,
        }


class MonetaryTransport(httpx.AsyncBaseTransport):
    """Reserve before each transmission through an existing provider transport.

    A trusted caller must verify all billable-token bounds in ``upper_bound``.
    This wrapper deliberately does not infer them from max_tokens or effort.
    Streaming/failure usage remains unknown until the caller records actual usage.
    """

    def __init__(
        self,
        budget: MonetaryBudget,
        upper_bound: Callable[[httpx.Request], int],
        *,
        mock_transport: httpx.MockTransport | None = None,
    ) -> None:
        if mock_transport is not None and type(mock_transport) is not httpx.MockTransport:
            raise BenchmarkError("ONLY_STANDARD_MOCK_TRANSPORT_ALLOWED")
        self.inner: httpx.AsyncBaseTransport = (
            mock_transport if mock_transport is not None else httpx.AsyncHTTPTransport(retries=0)
        )
        self.budget = budget
        self.upper_bound = upper_bound

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
        attempt = self.budget.reserve(self.upper_bound(request))
        response = await self.inner.handle_async_request(request)
        response.extensions["monetary_attempt_id"] = attempt
        return response

    async def aclose(self) -> None:
        await self.inner.aclose()
