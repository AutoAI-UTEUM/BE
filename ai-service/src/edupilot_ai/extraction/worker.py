"""One-shot PDF worker: bounded resources, safe JSON protocol, no service secrets."""

import json
import math
import resource
import sys
from dataclasses import asdict
from pathlib import Path

from edupilot_ai.extraction.pdf import (
    MAX_EXTRACTED_CHARS,
    PdfExtractionError,
    PdfPageLimitError,
    extract_pdf,
)

MEMORY_LIMIT_BYTES = 512 * 1024 * 1024


def set_resource_limits(timeout_seconds: float) -> None:
    """Linux production enforces address-space, CPU and core-dump limits."""
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    cpu_seconds = max(1, math.ceil(timeout_seconds))
    resource.setrlimit(resource.RLIMIT_CPU, (cpu_seconds, cpu_seconds))
    # macOS does not reliably enforce RLIMIT_AS; deployment and CI run Linux.
    if sys.platform == "linux":
        resource.setrlimit(resource.RLIMIT_AS, (MEMORY_LIMIT_BYTES, MEMORY_LIMIT_BYTES))


def main() -> None:
    """Never serialize a parser exception or traceback back to the parent."""
    try:
        path, max_pages, min_chars, min_ratio, timeout = sys.argv[1:]
        set_resource_limits(float(timeout))
        document = extract_pdf(
            Path(path),
            max_pages=int(max_pages),
            min_chars_per_page=int(min_chars),
            min_meaningful_page_ratio=float(min_ratio),
            max_text_chars=MAX_EXTRACTED_CHARS,
        )
        result: dict[str, object] = {"status": "OK", "pages": asdict(document)["pages"]}
    except PdfPageLimitError as error:
        result = {"status": "PAGE_LIMIT", "page_count": error.page_count}
    except PdfExtractionError as error:
        result = {"status": error.reason.value}
    except Exception:
        result = {"status": "RESOURCE_LIMIT"}
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
