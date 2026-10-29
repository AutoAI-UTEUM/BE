"""Atomic pre-transmission bounds: concurrency, restarts and ambiguous failures."""

from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx
import pytest

from tests.benchmarks.common import BenchmarkError
from tests.benchmarks.money import MonetaryBudget, MonetaryTransport


def test_parallel_money_limit_and_restart_preserve_reservations(tmp_path: Path) -> None:
    path = tmp_path / "budget.sqlite"
    budget = MonetaryBudget.create(path, call_cap=20, cost_cap=500)

    def reserve() -> bool:
        try:
            MonetaryBudget(path).reserve(60)
            return True
        except BenchmarkError:
            return False

    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda _: reserve(), range(40)))
    assert sum(results) == 8
    assert MonetaryBudget(path).summary()["costReserved"] == 480
    assert budget.summary()["unknownAttempts"] == 8
    assert budget.summary()["totalActualCost"] is None


def test_global_attempt_cap_includes_failed_retry_transmissions(tmp_path: Path) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=1000)
    for _ in range(20):
        attempt = budget.reserve(1)
        budget.record_actual(attempt, None)
    with pytest.raises(BenchmarkError, match="BUDGET_EXHAUSTED"):
        budget.reserve(1)
    assert budget.summary()["callsReserved"] == 20
    assert budget.summary()["costReserved"] == 20
    assert budget.summary()["totalActualCost"] is None


def test_actual_zero_and_unknown_never_release_worst_case(tmp_path: Path) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=100)
    first = budget.reserve(50)
    second = budget.reserve(50)
    budget.record_actual(first, 0)
    budget.record_actual(second, None)
    assert budget.summary()["knownActualCost"] == 0
    assert budget.summary()["unknownAttempts"] == 1
    assert budget.summary()["costReserved"] == 100
    with pytest.raises(BenchmarkError, match="BUDGET_EXHAUSTED"):
        budget.reserve(1)
    with pytest.raises(BenchmarkError, match="ALREADY_RECONCILED"):
        budget.record_actual(second, 0)


def test_unexpected_provider_cost_persistently_blocks_future_attempts(tmp_path: Path) -> None:
    path = tmp_path / "budget"
    budget = MonetaryBudget.create(path, call_cap=20, cost_cap=1000)
    attempt = budget.reserve(50)
    with pytest.raises(BenchmarkError, match="EXCEEDED_VERIFIED_BOUND"):
        budget.record_actual(attempt, 51)
    assert MonetaryBudget(path).summary()["blocked"] == 1
    with pytest.raises(BenchmarkError, match="BUDGET_EXHAUSTED"):
        MonetaryBudget(path).reserve(1)


@pytest.mark.parametrize("bound", [0, -1, True, 1.5, 2**63])
def test_invalid_or_unverified_bound_consumes_no_capacity(tmp_path: Path, bound: int) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=100)
    with pytest.raises(BenchmarkError, match="VERIFIED_POSITIVE_COST_BOUND"):
        budget.reserve(bound)
    assert budget.summary()["callsReserved"] == 0


def test_known_actual_ledger_and_unknown_attempt(tmp_path: Path) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=2, cost_cap=100)
    first = budget.reserve(50)
    budget.record_actual(first, 12)
    assert budget.summary()["totalActualCost"] == 12
    with pytest.raises(BenchmarkError, match="UNKNOWN_ATTEMPT"):
        budget.record_actual(999, 0)
    assert budget.summary()["costReserved"] == 50


@pytest.mark.parametrize("cap", [0, -1, True, 1.5, 50_000_000_001])
def test_more_than_five_usd_or_invalid_cap_rejected_before_file_creation(
    tmp_path: Path, cap: int
) -> None:
    path = tmp_path / "budget"
    with pytest.raises(BenchmarkError, match="AT_MOST_FIVE_USD"):
        MonetaryBudget.create(path, call_cap=20, cost_cap=cap)
    assert not path.exists()


def test_parallel_reconciliation_is_once_only(tmp_path: Path) -> None:
    path = tmp_path / "budget"
    budget = MonetaryBudget.create(path, call_cap=20, cost_cap=1000)
    attempt = budget.reserve(50)

    def reconcile() -> bool:
        try:
            MonetaryBudget(path).record_actual(attempt, 10)
            return True
        except BenchmarkError:
            return False

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: reconcile(), range(16)))
    assert sum(results) == 1
    assert budget.summary()["totalActualCost"] == 10


def test_summary_counts_share_a_snapshot_during_reservations(tmp_path: Path) -> None:
    path = tmp_path / "budget"
    budget = MonetaryBudget.create(path, call_cap=20, cost_cap=1000)

    def reserve_all() -> None:
        for _ in range(20):
            MonetaryBudget(path).reserve(1)

    with ThreadPoolExecutor(max_workers=2) as pool:
        writing = pool.submit(reserve_all)
        for _ in range(100):
            snapshot = budget.summary()
            assert snapshot["callsReserved"] == snapshot["unknownAttempts"]
            assert snapshot["costReserved"] == snapshot["callsReserved"]
        writing.result()
    assert budget.summary()["callsReserved"] == 20


@pytest.mark.asyncio
async def test_transport_reserves_before_io_and_blocks_retry_when_money_exhausted(
    tmp_path: Path,
) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=100)
    calls = 0

    def provider(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        assert budget.summary()["costReserved"] == 60
        raise httpx.ConnectError("synthetic connection failure")

    transport = MonetaryTransport(
        budget, lambda request: 60, mock_transport=httpx.MockTransport(provider)
    )
    async with httpx.AsyncClient(transport=transport) as client:
        with pytest.raises(httpx.ConnectError):
            await client.post("https://api.x.ai/v1/chat/completions", json={})
        with pytest.raises(BenchmarkError, match="BUDGET_EXHAUSTED"):
            await client.post("https://api.x.ai/v1/chat/completions", json={})
    assert calls == 1
    assert budget.summary()["unknownAttempts"] == 1


@pytest.mark.asyncio
async def test_unproven_bound_or_unexpected_route_never_transmitted(tmp_path: Path) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=100)

    def unproven(request: httpx.Request) -> int:
        raise BenchmarkError("BILLABLE_REASONING_BOUND_UNVERIFIED")

    def provider(request: httpx.Request) -> httpx.Response:
        pytest.fail("no unproven-cost transmission permitted")

    transport = MonetaryTransport(budget, unproven, mock_transport=httpx.MockTransport(provider))
    async with httpx.AsyncClient(transport=transport) as client:
        with pytest.raises(BenchmarkError, match="BOUND_UNVERIFIED"):
            await client.post("https://api.x.ai/v1/chat/completions", json={})
        with pytest.raises(BenchmarkError, match="UNEXPECTED_PROVIDER_ROUTE"):
            await client.post("https://api.x.ai/v1/files", json={})
    assert budget.summary()["callsReserved"] == 0


@pytest.mark.asyncio
async def test_response_attempt_id_links_usage_without_releasing_capacity(tmp_path: Path) -> None:
    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=100)
    transport = MonetaryTransport(
        budget,
        lambda request: 50,
        mock_transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"cost": 7})),
    )
    async with httpx.AsyncClient(transport=transport) as client:
        response = await client.post("https://api.x.ai/v1/responses", json={})
        budget.record_actual(response.extensions["monetary_attempt_id"], response.json()["cost"])
    assert budget.summary()["totalActualCost"] == 7
    assert budget.summary()["costReserved"] == 50


def test_custom_inner_cannot_hide_multiple_transmissions(tmp_path: Path) -> None:
    class HiddenRetryMock(httpx.MockTransport):
        pass

    budget = MonetaryBudget.create(tmp_path / "budget", call_cap=20, cost_cap=100)
    with pytest.raises(BenchmarkError, match="ONLY_STANDARD_MOCK_TRANSPORT"):
        MonetaryTransport(
            budget,
            lambda request: 1,
            mock_transport=HiddenRetryMock(lambda request: httpx.Response(200)),
        )
    assert budget.summary()["callsReserved"] == 0
