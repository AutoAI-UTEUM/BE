"""Render the real Compose files without a daemon, secrets, or provider calls."""

import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest

from edupilot_ai.settings import Settings

_ROOT = Path(__file__).resolve().parents[2]
_ENABLED = "EDUPILOT_EXPLAINER_PAGE_CONTEXT_ONLY_ENABLED"
_PAGES = "EDUPILOT_EXPLAINER_PAGE_CONTEXT_ONLY_PAGES"


@pytest.fixture(scope="module")
def compose_command() -> list[str]:
    standalone = os.environ.get("EDUPILOT_TEST_COMPOSE_BINARY")
    if standalone:
        return [standalone]
    docker = shutil.which("docker")
    if docker is None:
        pytest.skip("Docker Compose is required for deployment configuration tests")
    command = [docker, "compose"]
    version = subprocess.run(  # noqa: S603
        [*command, "version"], capture_output=True, check=False, timeout=15
    )
    if version.returncode:
        pytest.skip("Docker Compose plugin is unavailable")
    return command


def _render(
    command: list[str], env_file: Path, overrides: dict[str, str], overlay: bool
) -> dict[str, Any]:
    # Do not inherit real .env files, shell credentials, or feature flags.
    environment = {"PATH": os.defpath, "TAG": "test-sha", "ENVIRONMENT": "dev", **overrides}
    args = [*command, "--env-file", str(env_file), "--profile", "ai", "-f", "docker-compose.yml"]
    if overlay:
        args.extend(["-f", "docker-compose.prod.yml"])
    rendered = subprocess.run(  # noqa: S603
        [*args, "config", "--format", "json"],
        cwd=_ROOT,
        env=environment,
        capture_output=True,
        text=True,
        check=True,
        timeout=30,
    )
    result: dict[str, Any] = json.loads(rendered.stdout)
    return result


@pytest.mark.parametrize("overlay", [False, True], ids=["local", "dev-prod-overlay"])
@pytest.mark.parametrize("source", ["unset", "empty-map", "env-file", "shell-override"])
def test_compose_passes_explainer_settings_only_to_ai(
    compose_command: list[str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    overlay: bool,
    source: str,
    settings: Settings,
) -> None:
    env_file = tmp_path / "fake.env"
    env_file.write_text("", encoding="utf-8")
    pages = {"file-reviewed-example": [2, 15]}
    overrides: dict[str, str] = {}
    if source == "empty-map":
        env_file.write_text(f"{_ENABLED}=true\n{_PAGES}='{{}}'\n", encoding="utf-8")
    elif source in {"env-file", "shell-override"}:
        env_file.write_text(f"{_ENABLED}=true\n{_PAGES}='{json.dumps(pages)}'\n", encoding="utf-8")
    if source == "shell-override":
        overrides = {_ENABLED: "false", _PAGES: "{}"}

    services = _render(compose_command, env_file, overrides, overlay)["services"]
    environment = services["ai-service"]["environment"]
    for key in (_ENABLED, _PAGES):
        assert key not in services["main-service"]["environment"]
        value = environment.get(key)
        if value is None:
            monkeypatch.delenv(key, raising=False)
        else:
            monkeypatch.setenv(key, value)
    if source == "unset":
        assert environment.get(_PAGES) is None
    elif source == "env-file":
        assert json.loads(environment[_PAGES]) == pages
    else:
        assert environment[_PAGES] == "{}"

    loaded = Settings(
        _env_file=None,
        edupilot_internal_token=settings.edupilot_internal_token,
        xai_api_key=settings.xai_api_key,
    )
    assert loaded.edupilot_explainer_page_context_only_enabled == (
        source in {"env-file", "empty-map"}
    )
    assert loaded.edupilot_explainer_page_context_only_pages == (
        pages if source == "env-file" else {}
    )
