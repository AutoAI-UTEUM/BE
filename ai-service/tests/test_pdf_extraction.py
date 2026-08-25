"""Unit tests for the persistence-free PDF extraction core."""

from pathlib import Path

import pytest

from edupilot_ai.extraction import (
    PdfExtractionError,
    PdfFailureReason,
    PdfPageLimitError,
    clean_page_text,
    extract_pdf,
)
from tests.pdf_factory import make_blank_pdf, make_pdf


def test_clean_page_text_normalizes_without_collapsing_content() -> None:
    assert clean_page_text(" first  \r\nsecond\t \r\n\r\n") == "first\nsecond"


def test_extract_pdf_returns_text_and_preserves_blank_page(tmp_path: Path) -> None:
    pdf_path = tmp_path / "normal.pdf"
    page_text = "EduPilot page one contains enough explanatory text for normal extraction."
    pdf_path.write_bytes(make_pdf(page_text, None))

    document = extract_pdf(
        pdf_path,
        max_pages=300,
        min_chars_per_page=50,
        min_meaningful_page_ratio=0.05,
    )

    assert document.page_count == 2
    assert document.pages[0].page_number == 1
    assert document.pages[0].text == page_text
    assert document.pages[1].page_number == 2
    assert document.pages[1].text == ""


def test_extract_pdf_rejects_all_blank_pages(tmp_path: Path) -> None:
    pdf_path = tmp_path / "scanned.pdf"
    pdf_path.write_bytes(make_blank_pdf(2))

    with pytest.raises(PdfExtractionError) as caught:
        extract_pdf(
            pdf_path,
            max_pages=300,
            min_chars_per_page=50,
            min_meaningful_page_ratio=0.05,
        )

    assert caught.value.reason is PdfFailureReason.NO_TEXT


def test_extract_pdf_rejects_encrypted_document(tmp_path: Path) -> None:
    pdf_path = tmp_path / "encrypted.pdf"
    pdf_path.write_bytes(make_pdf("secret text", password="not-a-real-secret"))

    with pytest.raises(PdfExtractionError) as caught:
        extract_pdf(
            pdf_path,
            max_pages=300,
            min_chars_per_page=50,
            min_meaningful_page_ratio=0.05,
        )

    assert caught.value.reason is PdfFailureReason.ENCRYPTED


def test_extract_pdf_rejects_page_limit_before_text_extraction(tmp_path: Path) -> None:
    pdf_path = tmp_path / "too-many-pages.pdf"
    pdf_path.write_bytes(make_blank_pdf(301))

    with pytest.raises(PdfPageLimitError) as caught:
        extract_pdf(
            pdf_path,
            max_pages=300,
            min_chars_per_page=50,
            min_meaningful_page_ratio=0.05,
        )

    assert caught.value.page_count == 301
    assert caught.value.max_pages == 300


def test_extract_pdf_classifies_corrupted_document(tmp_path: Path) -> None:
    pdf_path = tmp_path / "corrupted.pdf"
    pdf_path.write_bytes(b"%PDF-this-is-not-a-valid-pdf")

    with pytest.raises(PdfExtractionError) as caught:
        extract_pdf(
            pdf_path,
            max_pages=300,
            min_chars_per_page=50,
            min_meaningful_page_ratio=0.05,
        )

    assert caught.value.reason is PdfFailureReason.CORRUPTED
