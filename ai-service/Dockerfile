FROM ghcr.io/astral-sh/uv:python3.14-bookworm-slim AS builder

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

WORKDIR /app

COPY pyproject.toml uv.lock .python-version README.md ./
COPY src ./src

RUN uv sync --frozen --no-dev

FROM python:3.14-slim-bookworm AS runtime

ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONPATH=/app/src \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=Asia/Seoul

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 edupilot \
    && useradd --uid 10001 --gid edupilot --create-home \
        --home-dir /home/edupilot --shell /usr/sbin/nologin edupilot \
    && mkdir -p /app \
    && chown -R edupilot:edupilot /app

WORKDIR /app

COPY --from=builder --chown=edupilot:edupilot /app/.venv ./.venv
COPY --chown=edupilot:edupilot src ./src

USER edupilot:edupilot

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=12 \
    CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3).status==200 else 1)"]

CMD ["uvicorn", "edupilot_ai.factory:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
