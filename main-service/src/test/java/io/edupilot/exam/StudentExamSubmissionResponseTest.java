package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.exam.dto.StudentExamSubmissionResponse;
import io.edupilot.quiz.QuizOption;
import io.edupilot.quiz.RubricCriterion;

class StudentExamSubmissionResponseTest {

	private static final Instant SUBMITTED_AT = Instant.parse("2026-09-09T01:02:03Z");
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void reviewMapsTypedCorrectAnswersWithoutLeakingPrivateAnswer() {
		ExamSubmission submission = submission();
		List<ExamAnswer> answers = List.of(
			answer(
				1,
				ExamQuestionType.MCQ,
				new ExamPublicQuestion("MCQ", List.of(
					new QuizOption("a", "선택지 A"), new QuizOption("b", "선택지 B")
				)),
				new ExamPrivateAnswer(
					"b", null, "객관식 해설", null, null,
					List.of(new RubricCriterion("비공개", BigDecimal.ONE))
				)
			),
			answer(
				2,
				ExamQuestionType.OX,
				new ExamPublicQuestion("OX", List.of()),
				new ExamPrivateAnswer(null, true, "OX 해설", null, null, List.of())
			),
			answer(
				3,
				ExamQuestionType.SHORT,
				new ExamPublicQuestion("SHORT", List.of()),
				new ExamPrivateAnswer(null, null, "단답 해설", "참고 답안", null, List.of())
			),
			answer(
				4,
				ExamQuestionType.ESSAY,
				new ExamPublicQuestion("ESSAY", List.of()),
				new ExamPrivateAnswer(null, null, null, "대체 답안", "모범 답안", List.of())
			)
		);

		JsonNode json = objectMapper.valueToTree(
			StudentExamSubmissionResponse.from(submission, answers, true)
		);

		assertThat(json.get("reviewAvailable").booleanValue()).isTrue();
		assertThat(json.at("/items/0/correctAnswer/choiceId").textValue()).isEqualTo("b");
		assertThat(json.at("/items/0/correctAnswer/text").textValue()).isEqualTo("선택지 B");
		assertThat(json.at("/items/1/correctAnswer").booleanValue()).isTrue();
		assertThat(json.at("/items/2/correctAnswer").textValue()).isEqualTo("참고 답안");
		assertThat(json.at("/items/3/correctAnswer").textValue()).isEqualTo("모범 답안");
		assertThat(json.at("/items/0/explanation").textValue()).isEqualTo("객관식 해설");
		assertThat(json.at("/items/3/explanation").isNull()).isTrue();
		assertThat(json.toString())
			.doesNotContain("rubric")
			.doesNotContain("privateAnswer")
			.doesNotContain("answerChoiceId")
			.doesNotContain("modelAnswer")
			.doesNotContain("referenceAnswer");
	}

	@Test
	void unavailableReviewOmitsCorrectAnswerAndExplanationKeys() {
		ExamSubmission submission = submission();
		ExamAnswer answer = answer(
			1,
			ExamQuestionType.MCQ,
			new ExamPublicQuestion("MCQ", List.of(new QuizOption("a", "선택지 A"))),
			new ExamPrivateAnswer("a", null, "숨겨진 해설", null, null, List.of())
		);

		JsonNode json = objectMapper.valueToTree(
			StudentExamSubmissionResponse.from(submission, List.of(answer), false)
		);

		assertThat(json.get("reviewAvailable").booleanValue()).isFalse();
		assertThat(json.at("/items/0").has("correctAnswer")).isFalse();
		assertThat(json.at("/items/0").has("explanation")).isFalse();
		assertThat(json.toString()).doesNotContain("rubric");
	}

	@Test
	void manualAdjustmentUsesEffectiveScoreWithoutExposingAdjustmentMetadata() {
		ExamSubmission submission = submission();
		when(submission.getScore()).thenReturn(new BigDecimal("8.00"));
		when(submission.getNormalizedScore()).thenReturn(new BigDecimal("80.00"));
		ExamAnswer answer = answer(
			1,
			ExamQuestionType.SHORT,
			new ExamPublicQuestion("SHORT", List.of()),
			new ExamPrivateAnswer(null, null, null, "참고 답안", null, List.of())
		);
		answer.recordManualScore(
			new BigDecimal("8.00"), 1L, Instant.parse("2026-09-09T02:00:00Z")
		);

		JsonNode json = objectMapper.valueToTree(
			StudentExamSubmissionResponse.from(submission, List.of(answer), true)
		);

		assertThat(json.at("/score").decimalValue()).isEqualByComparingTo("8.00");
		assertThat(json.at("/reviewAvailable").booleanValue()).isTrue();
		assertThat(json.at("/items/0/score").decimalValue()).isEqualByComparingTo("8.00");
		assertThat(json.at("/items/0/verdict").textValue()).isEqualTo("PARTIAL");
		assertThat(json.toString())
			.doesNotContain("manualScore")
			.doesNotContain("adjustedBy")
			.doesNotContain("adjustedAt");
	}

	private ExamSubmission submission() {
		ExamSubmission submission = mock(ExamSubmission.class);
		when(submission.getId()).thenReturn(10L);
		when(submission.getAttemptNo()).thenReturn(1);
		when(submission.getStatus()).thenReturn(SubmissionStatus.GRADED);
		when(submission.getScore()).thenReturn(new BigDecimal("10.00"));
		when(submission.getMaxScore()).thenReturn(new BigDecimal("40.00"));
		when(submission.getNormalizedScore()).thenReturn(new BigDecimal("25.00"));
		when(submission.getStartedAt()).thenReturn(SUBMITTED_AT.minusSeconds(90));
		when(submission.getDurationSeconds()).thenReturn(90);
		when(submission.getSubmittedAt()).thenReturn(SUBMITTED_AT);
		when(submission.getGradedAt()).thenReturn(SUBMITTED_AT.plusSeconds(1));
		return submission;
	}

	private ExamAnswer answer(
		int questionNo,
		ExamQuestionType type,
		ExamPublicQuestion publicQuestion,
		ExamPrivateAnswer privateAnswer
	) {
		ExamQuestion question = ExamQuestion.create(
			mock(Exam.class), questionNo, type, new BigDecimal("10.00"),
			publicQuestion, privateAnswer, "1.0"
		);
		ExamAnswer answer = ExamAnswer.create(
			mock(ExamSubmission.class), question, "제출 답안", new BigDecimal("10.00")
		);
		answer.recordGrade(new BigDecimal("10.00"), Verdict.CORRECT, null);
		return answer;
	}
}
