"""PDF byte fixtures generated entirely in test code."""

from io import BytesIO

from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject


def _escape_pdf_text(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def make_pdf(*page_texts: str | None, password: str | None = None) -> bytes:
    """Build a small PDF with optional text on each page."""
    writer = PdfWriter()
    for text in page_texts:
        page = writer.add_blank_page(width=612, height=792)
        if text is None:
            continue

        font = DictionaryObject(
            {
                NameObject("/Type"): NameObject("/Font"),
                NameObject("/Subtype"): NameObject("/Type1"),
                NameObject("/BaseFont"): NameObject("/Helvetica"),
            }
        )
        resources = DictionaryObject(
            {
                NameObject("/Font"): DictionaryObject(
                    {NameObject("/F1"): writer._add_object(font)}
                )
            }
        )
        content = DecodedStreamObject()
        content.set_data(
            f"BT /F1 12 Tf 72 720 Td ({_escape_pdf_text(text)}) Tj ET".encode("latin-1")
        )
        page[NameObject("/Resources")] = resources
        page[NameObject("/Contents")] = writer._add_object(content)

    if password is not None:
        writer.encrypt(password)

    output = BytesIO()
    writer.write(output)
    return output.getvalue()


def make_blank_pdf(page_count: int) -> bytes:
    """Build a PDF containing only blank pages."""
    return make_pdf(*(None for _ in range(page_count)))
