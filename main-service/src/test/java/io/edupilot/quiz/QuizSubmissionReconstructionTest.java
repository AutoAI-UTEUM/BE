package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.material.LearningMaterial;
import io.edupilot.session.LearningSession;
import io.edupilot.user.User;

class QuizSubmissionReconstructionTest {

	private static final Instant SUBMITTED_AT =
		Instant.parse("2026-08-12T10:00:00Z");

	@ParameterizedTest
	@MethodSource("quizAnswerCases")
	void reconstructsStoredResultAndCanonicalAnswerForEveryQuizType(
		QuizType quizType,
		PrivateQuizQuestion privateQuestion,
		String submittedAnswer,
		String expectedCorrectAnswer,
		String expectedExplanation
	) {
		QuizSubmission submission = submission(
			quizType,
			privateQuestion,
			submittedAnswer
		);

		var response = QuizSubmissionReconstruction.from(submission)
			.toDetailResponse();

		assertThat(response.quizId()).isEqualTo(50L);
		assertThat(response.submissionId()).isEqualTo(200L);
		assertThat(response.submittedAt()).isEqualTo(SUBMITTED_AT);
		assertThat(response.score()).isEqualByComparingTo("5.00");
		assertThat(response.maxScore()).isEqualByComparingTo("10.00");
		assertThat(response.passed()).isFalse();
		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.questionId()).isEqualTo("q1");
			assertThat(item.submittedAnswer()).isEqualTo(submittedAnswer);
			assertThat(item.correctAnswer()).isEqualTo(expectedCorrectAnswer);
			assertThat(item.verdict()).isEqualTo(GradingVerdict.PARTIAL);
			assertThat(item.score()).isEqualByComparingTo("5.00");
			assertThat(item.maxScore()).isEqualByComparingTo("10.00");
			assertThat(item.feedback()).isEqualTo("부분적으로 맞았습니다.");
			assertThat(item.explanation()).isEqualTo(expectedExplanation);
		});
	}

	private QuizSubmission submission(
		QuizType quizType,
		PrivateQuizQuestion privateQuestion,
		String submittedAnswer
	) {
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		Quiz quiz = Quiz.create(
			session,
			1,
			"퀴즈",
			1,
			1,
			quizType,
			List.of(new PublicQuizQuestion(
				"q1",
				"문항",
				new BigDecimal("10.00"),
				null
			)),
			List.of(privateQuestion),
			"1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 50L);
		GradingResult result = new GradingResult(
			"1.0",
			new BigDecimal("5.00"),
			new BigDecimal("10.00"),
			List.of(new GradingItem(
				"q1",
				new BigDecimal("5.00"),
				new BigDecimal("10.00"),
				GradingVerdict.PARTIAL,
				"부분적으로 맞았습니다."
			))
		);
		QuizSubmission submission = QuizSubmission.create(
			quiz,
			owner,
			"request-1",
			List.of(new SubmittedAnswer("q1", submittedAnswer)),
			result,
			false
		);
		ReflectionTestUtils.setField(submission, "id", 200L);
		ReflectionTestUtils.setField(submission, "createdAt", SUBMITTED_AT);
		return submission;
	}

	private static Stream<Arguments> quizAnswerCases() {
		return Stream.of(
			Arguments.of(
				QuizType.MCQ,
				new PrivateQuizQuestion(
					"q1", "b", null, "선택지 해설", null,
					null, null, null
				),
				"a",
				"b",
				"선택지 해설"
			),
			Arguments.of(
				QuizType.OX,
				new PrivateQuizQuestion(
					"q1", null, true, "OX 해설", null,
					null, null, null
				),
				"false",
				"true",
				"OX 해설"
			),
			Arguments.of(
				QuizType.SHORT,
				new PrivateQuizQuestion(
					"q1", null, null, null, "기준 답안",
					List.of("핵심어"), null, null
				),
				"학습자 단답",
				"기준 답안",
				null
			),
			Arguments.of(
				QuizType.ESSAY,
				new PrivateQuizQuestion(
					"q1", null, null, null, null, null,
					List.of(new RubricCriterion(
						"논리성", new BigDecimal("1.0")
					)),
					"모범 답안"
				),
				"학습자 서술",
				"모범 답안",
				null
			)
		);
	}
}
