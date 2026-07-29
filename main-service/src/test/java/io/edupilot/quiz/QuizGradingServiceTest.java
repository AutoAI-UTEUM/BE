package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class QuizGradingServiceTest {

	@Mock
	private AiClient aiClient;

	@Test
	void gradesMcqDeterministicallyWithoutAi() {
		QuizGradingService service = new QuizGradingService(aiClient);
		PreparedQuizSubmission prepared = prepared(
			QuizType.MCQ,
			List.of(
				privateMcq("q1", "a"),
				privateMcq("q2", "b")
			),
			List.of(
				new SubmittedAnswer("q1", "a"),
				new SubmittedAnswer("q2", "a")
			)
		);

		GradingResult result = service.grade(prepared);

		assertThat(result.score()).isEqualByComparingTo("10");
		assertThat(result.maxScore()).isEqualByComparingTo("20");
		assertThat(result.items()).extracting(GradingItem::verdict)
			.containsExactly(GradingVerdict.CORRECT, GradingVerdict.WRONG);
		org.mockito.Mockito.verifyNoInteractions(aiClient);
	}

	@Test
	void gradesOxDeterministicallyWithoutAi() {
		QuizGradingService service = new QuizGradingService(aiClient);
		PreparedQuizSubmission prepared = prepared(
			QuizType.OX,
			List.of(
				privateOx("q1", true),
				privateOx("q2", false)
			),
			List.of(
				new SubmittedAnswer("q1", "true"),
				new SubmittedAnswer("q2", "true")
			)
		);

		GradingResult result = service.grade(prepared);

		assertThat(result.score()).isEqualByComparingTo("10");
		assertThat(result.items()).extracting(GradingItem::verdict)
			.containsExactly(GradingVerdict.CORRECT, GradingVerdict.WRONG);
		org.mockito.Mockito.verifyNoInteractions(aiClient);
	}

	@Test
	void acceptsValidAiResultAndRejectsMismatchedTotal() {
		QuizGradingService service = new QuizGradingService(aiClient);
		PreparedQuizSubmission prepared = prepared(
			QuizType.SHORT,
			List.of(privateShort("q1"), privateShort("q2")),
			List.of(
				new SubmittedAnswer("q1", "답1"),
				new SubmittedAnswer("q2", "답2")
			)
		);
		when(aiClient.grade(any())).thenReturn(
			gradeResponse(new BigDecimal("20.00")),
			gradeResponse(new BigDecimal("15.00"))
		);

		assertThatThrownBy(() -> service.grade(prepared))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.GRADING_RESULT_INVALID)
			);

		GradingResult result = service.grade(prepared);
		assertThat(result.score()).isEqualByComparingTo("15.00");
		assertThat(result.items()).extracting(GradingItem::verdict)
			.containsExactly(GradingVerdict.CORRECT, GradingVerdict.PARTIAL);
	}

	@Test
	void rejectsMissingDuplicateOutOfRangeAndUnknownVerdictAiItems() {
		QuizGradingService service = new QuizGradingService(aiClient);
		PreparedQuizSubmission prepared = prepared(
			QuizType.SHORT,
			List.of(privateShort("q1"), privateShort("q2")),
			List.of(
				new SubmittedAnswer("q1", "답1"),
				new SubmittedAnswer("q2", "답2")
			)
		);
		when(aiClient.grade(any())).thenReturn(
			responseWithItems(List.of(validItem("q1", "10.00", "CORRECT"))),
			responseWithItems(List.of(
				validItem("q1", "10.00", "CORRECT"),
				validItem("q1", "5.00", "PARTIAL")
			)),
			responseWithItems(List.of(
				validItem("q1", "11.00", "CORRECT"),
				validItem("q2", "5.00", "PARTIAL")
			)),
			responseWithItems(List.of(
				validItem("q1", "10.00", "UNKNOWN"),
				validItem("q2", "5.00", "PARTIAL")
			))
		);

		for (int index = 0; index < 4; index++) {
			assertThatThrownBy(() -> service.grade(prepared))
				.isInstanceOfSatisfying(BusinessException.class, exception ->
					assertThat(exception.errorCode())
						.isEqualTo(ErrorCode.GRADING_RESULT_INVALID)
				);
		}
	}

	private GradeResponse gradeResponse(BigDecimal total) {
		return new GradeResponse(
			"1.0",
			50L,
			"SHORT",
			total,
			new BigDecimal("20.00"),
			List.of(
				new GradeResponse.Item(
					"q1",
					new BigDecimal("10.00"),
					new BigDecimal("10.00"),
					"CORRECT",
					"정확합니다."
				),
				new GradeResponse.Item(
					"q2",
					new BigDecimal("5.00"),
					new BigDecimal("10.00"),
					"PARTIAL",
					"일부가 부족합니다."
				)
			),
			null
		);
	}

	private PreparedQuizSubmission prepared(
		QuizType type,
		List<PrivateQuizQuestion> privateQuestions,
		List<SubmittedAnswer> answers
	) {
		return new PreparedQuizSubmission(
			50L,
			100L,
			10L,
			type,
			"1.0",
			"request-1",
			List.of(
				new PublicQuizQuestion(
					"q1",
					"문항 1",
					new BigDecimal("10.00"),
					type == QuizType.MCQ
						? List.of(
							new QuizOption("a", "A"),
							new QuizOption("b", "B")
						)
						: null
				),
				new PublicQuizQuestion(
					"q2",
					"문항 2",
					new BigDecimal("10.00"),
					type == QuizType.MCQ
						? List.of(
							new QuizOption("a", "A"),
							new QuizOption("b", "B")
						)
						: null
				)
			),
			privateQuestions,
			answers,
			null
		);
	}

	private PrivateQuizQuestion privateMcq(String id, String correct) {
		return new PrivateQuizQuestion(
			id,
			correct,
			null,
			"설명",
			null,
			null,
			null,
			null
		);
	}

	private PrivateQuizQuestion privateOx(String id, boolean correct) {
		return new PrivateQuizQuestion(
			id,
			null,
			correct,
			"설명",
			null,
			null,
			null,
			null
		);
	}

	private PrivateQuizQuestion privateShort(String id) {
		return new PrivateQuizQuestion(
			id,
			null,
			null,
			null,
			"기준 답안",
			List.of("정확성"),
			null,
			null
		);
	}

	private GradeResponse responseWithItems(List<GradeResponse.Item> items) {
		BigDecimal score = items.stream()
			.map(GradeResponse.Item::score)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		return new GradeResponse(
			"1.0",
			50L,
			"SHORT",
			score,
			new BigDecimal("20.00"),
			items,
			null
		);
	}

	private GradeResponse.Item validItem(
		String questionId,
		String score,
		String verdict
	) {
		return new GradeResponse.Item(
			questionId,
			new BigDecimal(score),
			new BigDecimal("10.00"),
			verdict,
			"피드백"
		);
	}
}
