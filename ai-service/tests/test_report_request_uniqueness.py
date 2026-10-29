"""Ambiguous report references are rejected before any model call."""

from copy import deepcopy

import httpx
import pytest

from tests.fakes import FakeLlm
from tests.test_report_contract import generate_payload, query_request


@pytest.mark.parametrize("eligible", [True, False])
async def test_duplicate_criterion_eligibility_is_rejected_before_model(
    eligible: bool,
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = generate_payload()
    duplicate = deepcopy(payload["dataQuality"]["criterionEligibility"][0])
    duplicate["eligible"] = eligible
    payload["dataQuality"]["criterionEligibility"].append(duplicate)
    response = await client.post(
        "/internal/ai/reports/generate", json=payload, headers=auth_headers
    )
    assert response.status_code == 422
    assert fake_llm.calls == []


@pytest.mark.parametrize("contradictory", [True, False])
async def test_query_duplicate_evidence_is_rejected_before_model(
    contradictory: bool,
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
) -> None:
    payload = query_request().model_dump(mode="json", by_alias=True)
    duplicate = deepcopy(payload["evidence"][0])
    if contradictory:
        duplicate["fact"] = "PRIVATE-CONTRADICTORY-FACT"
        duplicate["sourceType"] = "QA"
    payload["evidence"].append(duplicate)
    response = await client.post("/internal/ai/reports/query", json=payload, headers=auth_headers)
    assert response.status_code == 422
    assert fake_llm.calls == []
    assert "PRIVATE-CONTRADICTORY-FACT" not in response.text
