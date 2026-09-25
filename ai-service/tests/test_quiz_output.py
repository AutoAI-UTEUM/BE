"""Type-specific provider schemas keep the full quiz contract and its validators."""

import json
from copy import deepcopy

import httpx
import pytest
from pydantic import ValidationError

from edupilot_ai.models.quiz import QuizGeneration, QuizType
from edupilot_ai.orchestration.quiz_output import quiz_output_model
from tests.fakes import FakeLlm
from tests.test_quiz_grading import make_quiz

_QUESTION_NAMES = {"McqQuestion", "OxQuestion", "ShortQuestion", "EssayQuestion"}


@pytest.mark.parametrize("quiz_type", list(QuizType))
def test_selected_type_only_schema_is_smaller_and_preserves_wire(quiz_type: QuizType) -> None:
    original_schema = QuizGeneration.model_json_schema(by_alias=True)
    model = quiz_output_model(quiz_type)
    schema = model.model_json_schema(by_alias=True)
    questions = schema["properties"]["questions"]

    assert issubclass(model, QuizGeneration)
    assert model is quiz_output_model(quiz_type)
    assert schema["properties"]["quizType"]["const"] == quiz_type.value
    assert questions["minItems"] == 5 and questions["maxItems"] == 10
    assert "anyOf" not in questions["items"]
    assert len(_QUESTION_NAMES & set(schema["$defs"])) == 1
    assert len(json.dumps(schema)) < len(json.dumps(original_schema))
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    assert model.model_validate(raw).model_dump(mode="json", by_alias=True) == (
        QuizGeneration.model_validate(raw).model_dump(mode="json", by_alias=True)
    )
    # Building cached subclasses must not mutate the request/response base model.
    assert QuizGeneration.model_json_schema(by_alias=True) == original_schema


@pytest.mark.parametrize("quiz_type", list(QuizType))
@pytest.mark.parametrize("invalid", ["wrong_type", "duplicate_id", "count", "too_few", "too_many"])
def test_specialization_does_not_weaken_generation_validation(
    quiz_type: QuizType, invalid: str
) -> None:
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    if invalid == "wrong_type":
        raw["quizType"] = "OX" if quiz_type is QuizType.MCQ else "MCQ"
    elif invalid == "duplicate_id":
        raw["questions"][1]["questionId"] = raw["questions"][0]["questionId"]
    elif invalid == "count":
        raw["questionCount"] = 6
    elif invalid == "too_few":
        raw["questions"] = raw["questions"][:4]
        raw["questionCount"] = 4
    else:
        raw["questions"] = [{**raw["questions"][0], "questionId": f"q-{i}"} for i in range(11)]
        raw["questionCount"] = 11
    with pytest.raises(ValidationError):
        quiz_output_model(quiz_type).model_validate(raw)


@pytest.mark.parametrize("quiz_type", list(QuizType))
def test_normalization_is_identical_to_original_model(quiz_type: QuizType) -> None:
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    raw["title"] = "  " + "제" * 300 + "  "
    raw["questions"][0]["points"] = 1.333
    if quiz_type is QuizType.SHORT:
        raw["questions"][0]["gradingCriteria"] = ["핵심 개념", "근거", "핵심 개념"]
    elif quiz_type is QuizType.ESSAY:
        raw["questions"][0]["rubric"] = [
            {"criterion": "근거", "weight": 0.3},
            {"criterion": "근거", "weight": 0.3},
            {"criterion": "설명", "weight": 0.4},
        ]
    output = quiz_output_model(quiz_type).model_validate(raw)
    assert output.model_dump() == QuizGeneration.model_validate(raw).model_dump()
    assert output.title == "제" * 255 and output.questions[0].points == 1.33


@pytest.mark.parametrize("quiz_type", [QuizType.MCQ, QuizType.ESSAY])
def test_answer_and_rubric_guards_still_run(quiz_type: QuizType) -> None:
    raw = make_quiz(quiz_type).model_dump(mode="json", by_alias=True)
    question = raw["questions"][0]
    if quiz_type is QuizType.MCQ:
        question["answerChoiceId"] = "missing-choice"
    else:
        question["rubric"] = [{"criterion": "근거", "weight": 0.2}]
    with pytest.raises(ValidationError):
        quiz_output_model(quiz_type).model_validate(raw)


@pytest.mark.parametrize("quiz_type", list(QuizType))
@pytest.mark.parametrize("streaming", [False, True])
async def test_quiz_generation_still_calls_once_with_same_evidence_and_output(
    client: httpx.AsyncClient,
    fake_llm: FakeLlm,
    auth_headers: dict[str, str],
    turn_payload: dict[str, object],
    quiz_type: QuizType,
    streaming: bool,
) -> None:
    payload = deepcopy(turn_payload)
    payload["event"] = {"eventType": "QUIZ_TYPE_SELECTED", "payload": {"quizType": quiz_type.value}}
    snapshot = payload["context"]
    assert isinstance(snapshot, dict)
    snapshot.update(xaiFileId="file-quiz-test", learnerConfidence="HIGH")
    quiz = make_quiz(quiz_type)
    fake_llm.queue(quiz)

    response = await client.post(
        "/internal/ai/turn",
        json=payload,
        headers={**auth_headers, "Accept": "application/x-ndjson"} if streaming else auth_headers,
    )

    assert response.status_code == 200
    if streaming:
        events = [json.loads(line) for line in response.text.splitlines()]
        assert events[-1]["type"] == "completed"
        assert not any(e["type"] == "content_delta" for e in events)
        body = events[-1]["result"]
    else:
        body = response.json()
    assert body["quiz"] == quiz.model_dump(mode="json", by_alias=True)
    assert body["messages"] == []
    assert body["actionsExecuted"][0]["agent"] == "QuizAgent"
    assert len(fake_llm.calls) == 1 and fake_llm.stream_calls == []
    assert fake_llm.response_models == [quiz_output_model(quiz_type)]
    assert fake_llm.file_attachments[0][0].file_id == "file-quiz-test"
    messages, _ = fake_llm.calls[0]
    system = messages[0]["content"]
    assert isinstance(system, str) and "문항은 5~10개" in system
    assert "응용 문항을 포함하라" in system
    user = messages[1]["content"]
    assert isinstance(user, str)
    assert json.loads(user)["pageContext"][0]["text"] == snapshot["currentPageText"]
