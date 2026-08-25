"""Deterministic normalization for generated quiz and exam-draft output."""

from typing import Any

from edupilot_ai.models.exam_draft import DraftEssayQuestion, DraftShortQuestion
from edupilot_ai.models.grading import GradeRequest
from edupilot_ai.models.quiz import (
    EssayQuestion,
    OxQuestion,
    QuizGeneration,
    QuizType,
    ShortQuestion,
)
from tests.test_quiz_grading import make_quiz


def test_quiz_title_is_trimmed_and_truncated_to_255_characters() -> None:
    payload = make_quiz(quiz_type=QuizType.MCQ).model_dump(by_alias=True)
    payload["title"] = f"  {'제' * 300}  "

    quiz = QuizGeneration.model_validate(payload)

    assert quiz.title == "제" * 255
    assert len(quiz.title) == 255


def test_question_points_are_rounded_to_two_decimal_places() -> None:
    question = OxQuestion(
        question_id="q-1",
        question_text="정규화할 점수인가요?",
        points=1.333,
        answer_value=True,
        explanation="소수 둘째 자리까지 사용합니다.",
    )

    assert question.points == 1.33


def test_essay_rubric_duplicate_criteria_are_merged() -> None:
    question = EssayQuestion.model_validate(
        {
            "questionId": "q-1",
            "questionText": "기준에 맞게 설명하세요.",
            "points": 10,
            "modelAnswer": "모범 답안",
            "rubric": [
                {"criterion": "정확성", "weight": 0.3},
                {"criterion": "정확성", "weight": 0.3},
                {"criterion": "논리성", "weight": 0.4},
            ],
        }
    )

    assert [(item.criterion, item.weight) for item in question.rubric] == [
        ("정확성", 0.6),
        ("논리성", 0.4),
    ]
    assert sum(item.weight for item in question.rubric) == 1.0


def test_short_grading_criteria_are_deduplicated_in_order() -> None:
    question = ShortQuestion(
        question_id="q-1",
        question_text="핵심을 쓰세요.",
        points=10,
        reference_answer="기준 답안",
        grading_criteria=["a", "b", "a"],
    )

    assert question.grading_criteria == ["a", "b"]


def test_short_single_duplicate_criterion_keeps_one_item() -> None:
    question = ShortQuestion(
        question_id="q-1",
        question_text="핵심을 쓰세요.",
        points=10,
        reference_answer="기준 답안",
        grading_criteria=["a", "a"],
    )

    assert question.grading_criteria == ["a"]


def test_exam_draft_subclasses_inherit_generation_normalization() -> None:
    short = DraftShortQuestion.model_validate(
        {
            "questionType": "SHORT",
            "questionId": "short-1",
            "questionText": "핵심을 쓰세요.",
            "points": 1.335,
            "referenceAnswer": "기준 답안",
            "gradingCriteria": ["a", "a"],
        }
    )
    essay = DraftEssayQuestion.model_validate(
        {
            "questionType": "ESSAY",
            "questionId": "essay-1",
            "questionText": "근거를 설명하세요.",
            "points": 10,
            "modelAnswer": "모범 답안",
            "rubric": [
                {"criterion": "근거", "weight": 0.5},
                {"criterion": "근거", "weight": 0.5},
            ],
        }
    )

    assert short.points == 1.33
    assert short.grading_criteria == ["a"]
    assert [(item.criterion, item.weight) for item in essay.rubric] == [("근거", 1.0)]


def test_grade_request_rubric_is_not_normalized() -> None:
    payload: dict[str, Any] = {
        "schemaVersion": "1.0",
        "quizId": 1,
        "quizType": "ESSAY",
        "items": [
            {
                "questionId": "q-1",
                "question": "근거를 설명하세요.",
                "modelAnswer": "모범 답안",
                "rubric": [
                    {"criterion": "근거", "weight": 0.5},
                    {"criterion": "근거", "weight": 0.5},
                ],
                "maxScore": 10,
            }
        ],
        "studentAnswers": [{"questionId": "q-1", "answer": "학생 답안"}],
    }

    request = GradeRequest.model_validate(payload)

    assert len(request.items[0].rubric) == 2
