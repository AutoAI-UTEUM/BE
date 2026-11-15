"""Explicit difficulty is scoped to one quiz, never learner memory."""

import json
from copy import deepcopy

import pytest
from pydantic import ValidationError

from edupilot_ai.models.quiz import QuizType
from edupilot_ai.models.turn import TurnRequest
from edupilot_ai.orchestration.context import ContextBuilder, PlanContext
from edupilot_ai.orchestration.prompts import quiz_messages


@pytest.mark.parametrize("difficulty", ["EASY", "MEDIUM", "HARD"])
@pytest.mark.parametrize("quiz_type", list(QuizType))
def test_difficulty_reaches_plan_and_quiz(
    turn_payload: dict[str, object], difficulty: str, quiz_type: QuizType
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "QUIZ_TYPE_SELECTED",
        "payload": {"quizType": quiz_type.value, "difficulty": difficulty},
    }
    context = ContextBuilder().build(TurnRequest.model_validate(payload))
    original_level = context.learner_level
    assert PlanContext.from_agent_context(context).event_payload["difficulty"] == difficulty
    messages = quiz_messages(context, quiz_type)
    assert json.loads(messages[1]["content"])["difficulty"] == difficulty
    assert f"difficulty={difficulty}" in messages[0]["content"]
    assert "이 선택을 우선" in messages[0]["content"]
    assert context.learner_level == original_level


@pytest.mark.parametrize("value", [None, "easy", "HARD ", "", 1, True, {}])
def test_invalid_difficulty(turn_payload: dict[str, object], value: object) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "QUIZ_TYPE_SELECTED",
        "payload": {"quizType": "MCQ", "difficulty": value},
    }
    with pytest.raises(ValidationError):
        TurnRequest.model_validate(payload)


def test_rejects_difficulty_on_question(turn_payload: dict[str, object]) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {
        "eventType": "USER_QUESTION",
        "payload": {"message": "질문", "difficulty": "HARD"},
    }
    with pytest.raises(ValidationError):
        TurnRequest.model_validate(payload)


def test_omitted_difficulty_preserves_behavior(turn_payload: dict[str, object]) -> None:
    context = ContextBuilder().build(TurnRequest.model_validate(turn_payload))
    messages = quiz_messages(context, QuizType.MCQ)
    assert "difficulty" not in json.loads(messages[1]["content"])
    assert "명시적으로 선택" not in messages[0]["content"]
    assert "learnerConfidence" in messages[0]["content"]
