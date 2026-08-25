"""Pure, persistence-free PDF text extraction."""

from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from pypdf import PdfReader
from pypdf.errors import FileNotDecryptedError, PdfReadError


class PdfFailureReason(StrEnum):
    """Internal reason retained without exposing parser exceptions."""

    CORRUPTED = "CORRUPTED"
    ENCRYPTED = "ENCRYPTED"
    NO_TEXT = "NO_TEXT"


class PdfExtractionError(Exception):
    """Expected PDF failure classified independently of HTTP."""

    def __init__(self, reason: PdfFailureReason) -> None:
        super().__init__(reason.value)
        self.reason = reason


class PdfPageLimitError(Exception):
    """Raised before page extraction when the contract limit is exceeded."""

    def __init__(self, *, page_count: int, max_pages: int) -> None:
        super().__init__("PDF page limit exceeded")
        self.page_count = page_count
        self.max_pages = max_pages


@dataclass(frozen=True, slots=True)
class ExtractedPageData:
    """Pure extraction output for one PDF page."""

    page_number: int
    text: str


@dataclass(frozen=True, slots=True)
class ExtractedDocument:
    """Pure extraction output for one PDF document."""

    pages: tuple[ExtractedPageData, ...]

    @property
    def page_count(self) -> int:
        return len(self.pages)


def clean_page_text(text: str) -> str:
    """Normalize line endings and trailing whitespace without rewriting content."""
    normalized = text.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    cleaned_lines = (line.rstrip() for line in normalized.split("\n"))
    return "\n".join(cleaned_lines).strip()


def extract_pdf(
    path: Path,
    *,
    max_pages: int,
    min_chars_per_page: int,
    min_meaningful_page_ratio: float,
) -> ExtractedDocument:
    """Extract all page text or raise one of the contract-safe domain errors."""
    try:
        reader = PdfReader(path, strict=False)
        if reader.is_encrypted:
            raise PdfExtractionError(PdfFailureReason.ENCRYPTED)

        page_count = len(reader.pages)
        if page_count > max_pages:
            raise PdfPageLimitError(page_count=page_count, max_pages=max_pages)

        pages = tuple(
            ExtractedPageData(
                page_number=index,
                text=clean_page_text(page.extract_text() or ""),
            )
            for index, page in enumerate(reader.pages, start=1)
        )
    except PdfExtractionError, PdfPageLimitError:
        raise
    except FileNotDecryptedError as exception:
        raise PdfExtractionError(PdfFailureReason.ENCRYPTED) from exception
    except (OSError, PdfReadError, TypeError, ValueError) as exception:
        raise PdfExtractionError(PdfFailureReason.CORRUPTED) from exception

    if not pages or all(not page.text for page in pages):
        raise PdfExtractionError(PdfFailureReason.NO_TEXT)

    meaningful_pages = sum(1 for page in pages if len(page.text) >= min_chars_per_page)
    if meaningful_pages / len(pages) < min_meaningful_page_ratio:
        raise PdfExtractionError(PdfFailureReason.NO_TEXT)

    return ExtractedDocument(pages=pages)
