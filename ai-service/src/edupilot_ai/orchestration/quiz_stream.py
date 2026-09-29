"""Incremental private JSON parsing; emit only complete validated public questions.

One provider call emits one object. Metadata must precede questions, which must
be the last field. Partial previews are never successful/persistable quiz results.
No regex brace splitting, raw provider text, or answer fields reach the event.
"""

import json
from math import isfinite
from typing import Any, Literal

from pydantic import Field, ValidationError, field_validator

from edupilot_ai.core.errors import ErrorCategory
from edupilot_ai.llm.bridge import LlmBridgeError
from edupilot_ai.models.base import ContractModel
from edupilot_ai.models.quiz import (
    EssayQuestion,
    McqQuestion,
    OxQuestion,
    QuizCoverage,
    QuizGeneration,
    QuizQuestion,
    QuizType,
    ShortQuestion,
)
from edupilot_ai.models.quiz_preview import PublicQuizQuestion, QuizQuestionStreamEvent
from edupilot_ai.orchestration.quiz_output import quiz_output_model

_MAX_JSON_CHARS = 1_048_576
_QUESTION_MODELS: dict[QuizType, type[QuizQuestion]] = {
    QuizType.MCQ: McqQuestion,
    QuizType.OX: OxQuestion,
    QuizType.SHORT: ShortQuestion,
    QuizType.ESSAY: EssayQuestion,
}


def invalid_quiz_stream() -> LlmBridgeError:
    return LlmBridgeError(category=ErrorCategory.SCHEMA, retryable=False)


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ValueError("non-finite JSON number")


def _finite_float(value: str) -> float:
    number = float(value)
    if not isfinite(number):
        raise ValueError("non-finite JSON number")
    return number


class _QuizHeader(ContractModel):
    schema_version: Literal["1.0"] = "1.0"
    generation_id: str = Field(min_length=1)
    quiz_type: QuizType
    coverage: QuizCoverage
    title: str = Field(min_length=1)
    question_count: Literal[5]

    @field_validator("title", mode="before")
    @classmethod
    def normalize_title(cls, value: object) -> object:
        return QuizGeneration.normalize_title(value)


class QuizStreamParser:
    """Bounded parser whose finish() is the only authority for the complete quiz."""

    def __init__(self, quiz_type: QuizType, coverage: QuizCoverage) -> None:
        self._quiz_type = quiz_type
        self._coverage = coverage
        self._buffer = ""
        self._received = 0
        self._state = "start"
        self._key = ""
        self._metadata: dict[str, Any] = {}
        self._questions: list[dict[str, Any]] = []
        self._ids: set[str] = set()
        self._header: _QuizHeader | None = None
        self._decoder = json.JSONDecoder(
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
            parse_float=_finite_float,
        )

    def feed(self, text: str) -> list[QuizQuestionStreamEvent]:
        self._received += len(text)
        if self._received > _MAX_JSON_CHARS:
            raise invalid_quiz_stream()
        self._buffer += text
        events: list[QuizQuestionStreamEvent] = []
        try:
            while self._step(events):
                pass
        except (ValueError, TypeError, KeyError, RecursionError) as error:
            # Validation exceptions may contain private values: never expose/log them.
            raise invalid_quiz_stream() from error
        return events

    def _step(self, events: list[QuizQuestionStreamEvent]) -> bool:
        self._buffer = self._buffer.lstrip(" \r\n\t")
        if not self._buffer:
            return False
        state = self._state
        if state == "done":
            raise ValueError("trailing JSON")
        transitions = {
            "start": ("{", "key"),
            "colon": (":", "array" if self._key == "questions" else "value"),
            "array": ("[", "question"),
            "end": ("}", "done"),
        }
        if state in transitions:
            expected, next_state = transitions[state]
            if self._buffer[0] != expected:
                raise ValueError("invalid JSON delimiter")
            self._buffer = self._buffer[1:]
            self._state = next_state
            return True
        if state in {"separator", "question_separator"}:
            marker = self._buffer[0]
            if marker == ",":
                self._state = "key" if state == "separator" else "question"
            elif marker == "]" and state == "question_separator":
                self._state = "end"
            else:
                raise ValueError("invalid JSON separator")
            self._buffer = self._buffer[1:]
            return True
        # raw_decode waits for a *complete* key/value/question (including escaped braces).
        try:
            value, end = self._decoder.raw_decode(self._buffer)
        except json.JSONDecodeError:
            return False
        self._buffer = self._buffer[end:]
        if state == "key":
            if not isinstance(value, str) or value in self._metadata:
                raise ValueError("invalid or duplicate field")
            self._key = value
            if value == "questions":
                self._header = _QuizHeader.model_validate(self._metadata)
                if (
                    self._header.quiz_type != self._quiz_type
                    or self._header.coverage != self._coverage
                ):
                    raise ValueError("unexpected quiz type or range")
            elif value not in _QuizHeader.model_json_schema(by_alias=True)["properties"]:
                raise ValueError("unknown field")
            self._state = "colon"
        elif state == "value":
            self._metadata[self._key] = value
            self._state = "separator"
        elif state == "question":
            if self._header is None or len(self._questions) >= 5:
                raise ValueError("invalid question count")
            question = _QUESTION_MODELS[self._quiz_type].model_validate(value)
            if not isfinite(question.points):
                raise ValueError("non-finite points")
            if question.question_id in self._ids:
                raise ValueError("duplicate questionId")
            self._ids.add(question.question_id)
            self._questions.append(question.model_dump(mode="json", by_alias=True))
            public = PublicQuizQuestion(
                question_id=question.question_id,
                question_text=question.question_text,
                points=question.points,
                choices=question.choices if isinstance(question, McqQuestion) else None,
            )
            events.append(
                QuizQuestionStreamEvent(
                    generation_id=self._header.generation_id,
                    quiz_type=self._quiz_type,
                    title=self._header.title,
                    coverage=self._header.coverage,
                    question_index=len(self._questions),
                    question=public,
                )
            )
            self._state = "question_separator"
        else:
            raise ValueError("invalid parser state")
        return True

    def finish(self) -> QuizGeneration:
        if self._state != "done" or self._buffer.strip():
            raise invalid_quiz_stream()
        try:
            return quiz_output_model(self._quiz_type).model_validate(
                {**self._metadata, "questions": self._questions}
            )
        except ValidationError as error:
            raise invalid_quiz_stream() from error
