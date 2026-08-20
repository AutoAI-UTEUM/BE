"""Run EduPilot outline and criteria agents directly from a local PDF."""

import argparse
import asyncio
import json
from collections.abc import Sequence
from pathlib import Path
from time import perf_counter

import httpx
from pydantic import ValidationError

from edupilot_ai.core.errors import InternalApiError
from edupilot_ai.criteria.service import CriteriaSuggestService
from edupilot_ai.extraction.pdf import (
    PdfExtractionError,
    PdfFailureReason,
    PdfPageLimitError,
    extract_pdf,
)
from edupilot_ai.llm.xai import XaiLlmBridge
from edupilot_ai.models.criteria import CriteriaMaterial, CriteriaSuggestRequest
from edupilot_ai.models.outline import OutlinePage, OutlineRequest, OutlineResponse
from edupilot_ai.outline.service import OutlineService
from edupilot_ai.settings import Settings

_CONFIG_ERROR_MESSAGE = (
    "ai-service/.env 파일에 XAI_API_KEY가 필요합니다. 팀에서 받아 넣어주세요 "
    "(이 파일은 절대 커밋 금지)."
)
_UNREADABLE_PDF_MESSAGE = (
    "이 PDF는 텍스트를 읽을 수 없습니다(스캔·이미지형). 다른 파일로 시도하세요."
)

# main-service ReportCriterionCatalog 기준
_DEFAULT_CRITERION_KEYS = [
    "concept_understanding",
    "question_specificity",
    "problem_solving",
    "application_transfer",
    "quiz_exam_accuracy",
    "learning_persistence",
    "error_reflection",
    "class_participation",
    "growth_trend",
]


class DemoConfigError(Exception):
    """Raised when the local demo configuration is unavailable."""


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="PDF에서 EduPilot 자료 개요 또는 평가 지표를 생성합니다."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    for command, help_text in (
        ("outline", "PDF를 추출하고 자료 개요를 생성합니다."),
        ("criteria", "PDF 개요를 만든 뒤 강의별 평가 지표를 생성합니다."),
    ):
        command_parser = subparsers.add_parser(command, help=help_text)
        command_parser.add_argument("pdf", type=Path, help="텍스트가 포함된 PDF 경로")
    return parser


def _load_settings() -> Settings:
    if not Path(".env").is_file():
        raise DemoConfigError
    try:
        return Settings()
    except ValidationError as error:
        raise DemoConfigError from error


def _outline_service(*, settings: Settings, llm: XaiLlmBridge) -> OutlineService:
    return OutlineService(
        llm=llm,
        profile=settings.outline_llm_profile,
        timeout_seconds=settings.edupilot_outline_timeout_seconds,
        max_chars_per_page=settings.edupilot_outline_max_chars_per_page,
        min_chars_per_page=settings.edupilot_extract_min_chars_per_page,
        min_meaningful_page_ratio=settings.edupilot_extract_min_meaningful_page_ratio,
    )


def _criteria_service(*, settings: Settings, llm: XaiLlmBridge) -> CriteriaSuggestService:
    return CriteriaSuggestService(
        llm=llm,
        profile=settings.criteria_llm_profile,
        timeout_seconds=settings.edupilot_criteria_timeout_seconds,
    )


def _extract(pdf_path: Path, settings: Settings) -> OutlineRequest:
    document = extract_pdf(
        pdf_path,
        max_pages=settings.edupilot_extract_max_pages,
        min_chars_per_page=settings.edupilot_extract_min_chars_per_page,
        min_meaningful_page_ratio=settings.edupilot_extract_min_meaningful_page_ratio,
    )
    print(f"추출 중... {document.page_count}페이지", flush=True)
    return OutlineRequest(
        schema_version="1.0",
        total_pages=document.page_count,
        pages=[
            OutlinePage(page_number=page.page_number, text=page.text) for page in document.pages
        ],
    )


async def _generate_outline(
    service: OutlineService,
    request: OutlineRequest,
) -> OutlineResponse:
    started_at = perf_counter()
    response = await service.execute(request)
    duration = perf_counter() - started_at
    print(f"개요 생성 중... 완료 {duration:.1f}s", flush=True)
    return response


async def _run(command: str, pdf_path: Path, settings: Settings) -> None:
    outline_request = _extract(pdf_path, settings)
    async with httpx.AsyncClient() as client:
        llm = XaiLlmBridge(client=client, api_key=settings.xai_api_key)
        outline = await _generate_outline(
            _outline_service(settings=settings, llm=llm),
            outline_request,
        )
        if command == "outline":
            result_payload = outline.model_dump(mode="json", by_alias=True)
        else:
            criteria_request = CriteriaSuggestRequest(
                schema_version="1.0",
                existing_criterion_keys=_DEFAULT_CRITERION_KEYS,
                materials=[
                    CriteriaMaterial(
                        title=pdf_path.stem,
                        material_summary=outline.material_summary,
                        sections=outline.sections,
                    )
                ],
            )
            started_at = perf_counter()
            criteria = await _criteria_service(settings=settings, llm=llm).execute(criteria_request)
            duration = perf_counter() - started_at
            print(f"지표 생성 중... 완료 {duration:.1f}s", flush=True)
            result_payload = criteria.model_dump(mode="json", by_alias=True)

    print(
        json.dumps(
            result_payload,
            ensure_ascii=False,
            indent=2,
        )
    )


def _internal_error_message(error: InternalApiError) -> str:
    if error.code in {"INSUFFICIENT_TEXT", "NO_TEXT_CONTENT"}:
        return _UNREADABLE_PDF_MESSAGE
    if error.code == "AI_SERVICE_TIMEOUT":
        return "AI 응답 시간이 초과되었습니다. 잠시 후 다시 시도하세요."
    if error.code == "AI_RESPONSE_INVALID":
        return "AI 결과를 확인할 수 없습니다. 잠시 후 다시 시도하세요."
    return "AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도하세요."


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        settings = _load_settings()
        pdf_path = Path(args.pdf)
        if not pdf_path.is_file():
            raise FileNotFoundError(pdf_path)
        asyncio.run(_run(str(args.command), pdf_path, settings))
    except DemoConfigError:
        print(_CONFIG_ERROR_MESSAGE)
        return 2
    except FileNotFoundError:
        print("PDF 파일을 찾을 수 없습니다. 경로를 확인해 주세요.")
        return 2
    except PdfPageLimitError as error:
        print(f"PDF가 페이지 상한({error.max_pages}페이지)을 초과했습니다.")
        return 2
    except PdfExtractionError as error:
        if error.reason is PdfFailureReason.ENCRYPTED:
            print("암호화된 PDF입니다. 암호를 해제한 파일로 다시 시도하세요.")
        elif error.reason is PdfFailureReason.NO_TEXT:
            print(_UNREADABLE_PDF_MESSAGE)
        else:
            print("PDF를 읽을 수 없습니다. 정상적인 PDF 파일인지 확인해 주세요.")
        return 2
    except InternalApiError as error:
        print(_internal_error_message(error))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
