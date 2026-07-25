"""Health endpoint contract."""

import httpx


async def test_health_is_available_without_internal_auth(client: httpx.AsyncClient) -> None:
    response = await client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
    assert response.headers["X-Trace-Id"]
