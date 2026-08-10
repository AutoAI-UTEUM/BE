package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.quiz.dto.QuizGradingResultResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.UiAction;

@ExtendWith(MockitoExtension.class)
class QuizSubmissionServiceTest {

	@Mock
	private QuizSubmissionPreparationService preparationService;

	@Mock
	private QuizGradingService gradingService;

	@Mock
	private QuizSubmissionPersistenceService persistenceService;

	@Mock
	private QuizPostGradingHook postGradingHook;

	@Test
	void appliesConfiguredPassRatioWithoutDivisionRounding() {
		PreparedQuizSubmission prepared = new PreparedQuizSubmission(
			50L,
			100L,
			10L,
			QuizType.MCQ,
			"1.0",
			"request-1",
			List.of(),
			List.of(),
			List.of(),
			null
		);
		QuizSubmitRequest request = new QuizSubmitRequest("request-1", List.of());
		GradingResult sixty = new GradingResult(
			"1.0",
			new BigDecimal("60.00"),
			new BigDecimal("100.00"),
			List.of()
		);
		when(preparationService.prepare(1L, 50L, request)).thenReturn(prepared);
		when(gradingService.grade(prepared)).thenReturn(sixty);
		when(persistenceService.persist(1L, prepared, sixty, true))
			.thenReturn(new PersistedQuizSubmission(new QuizSubmitResponse(
				200L,
				50L,
				QuizType.MCQ,
				sixty.score(),
				sixty.maxScore(),
				true,
				QuizGradingResultResponse.from(sixty),
				List.of(UiAction.moveNextPage())
			), true));
		when(postGradingHook.onGraded(any()))
			.thenReturn(List.of(UiAction.moveNextPage()));
		QuizSubmissionService service = new QuizSubmissionService(
			preparationService,
			gradingService,
			persistenceService,
			new QuizProperties(new BigDecimal("0.6"), 200),
			postGradingHook
		);

		QuizSubmitResponse response = service.submit(1L, 50L, request);

		assertThat(response.passed()).isTrue();
		verify(persistenceService).persist(
			eq(1L),
			eq(prepared),
			eq(sixty),
			eq(true)
		);
	}

	@Test
	void scoreBelowSixtyPercentFailsAndEightyPercentConfigIsHonored() {
		assertPassDecision("59.00", "0.6", false);
		org.mockito.Mockito.reset(
			preparationService,
			gradingService,
			persistenceService
		);
		assertPassDecision("80.00", "0.8", true);
	}

	@Test
	void invalidGradeDoesNotPersistAndSameRequestCanSucceedLater() {
		PreparedQuizSubmission prepared = prepared();
		QuizSubmitRequest request = new QuizSubmitRequest("request-1", List.of());
		GradingResult valid = result("100.00");
		when(preparationService.prepare(1L, 50L, request)).thenReturn(prepared);
		when(gradingService.grade(prepared))
			.thenThrow(new BusinessException(ErrorCode.GRADING_RESULT_INVALID))
			.thenReturn(valid);
		QuizSubmitResponse persisted = response(valid, true);
		when(persistenceService.persist(1L, prepared, valid, true))
			.thenReturn(new PersistedQuizSubmission(persisted, true));
		when(postGradingHook.onGraded(any()))
			.thenReturn(List.of(UiAction.moveNextPage()));
		QuizSubmissionService service = service("0.6");

		assertThatThrownBy(() -> service.submit(1L, 50L, request))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.GRADING_RESULT_INVALID)
			);
		verify(persistenceService, never()).persist(
			eq(1L),
			eq(prepared),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyBoolean()
		);

		assertThat(service.submit(1L, 50L, request)).isEqualTo(persisted);
	}

	@Test
	void postGradingFailureReturnsPersistedSubmissionWithDefaultAction() {
		PreparedQuizSubmission prepared = prepared();
		QuizSubmitRequest request = new QuizSubmitRequest(
			"request-1",
			List.of()
		);
		GradingResult result = result("40.00");
		when(preparationService.prepare(1L, 50L, request))
			.thenReturn(prepared);
		when(gradingService.grade(prepared)).thenReturn(result);
		when(persistenceService.persist(1L, prepared, result, false))
			.thenReturn(new PersistedQuizSubmission(
				response(result, false),
				true
			));
		when(postGradingHook.onGraded(any()))
			.thenThrow(new IllegalStateException("pipeline failed"));

		QuizSubmitResponse actual = service("0.6").submit(
			1L,
			50L,
			request
		);

		assertThat(actual.submissionId()).isEqualTo(200L);
		assertThat(actual.uiActions())
			.containsExactly(UiAction.moveNextPage());
	}

	@Test
	void offPageQuizSubmissionSkipsLearningSupportPipeline() {
		PreparedQuizSubmission prepared = prepared();
		QuizSubmitRequest request = new QuizSubmitRequest(
			"request-1",
			List.of()
		);
		GradingResult result = result("100.00");
		QuizSubmitResponse response = response(result, true);
		when(preparationService.prepare(1L, 50L, request))
			.thenReturn(prepared);
		when(gradingService.grade(prepared)).thenReturn(result);
		when(persistenceService.persist(1L, prepared, result, true))
			.thenReturn(new PersistedQuizSubmission(response, false));

		assertThat(service("0.6").submit(1L, 50L, request))
			.isEqualTo(response);
		verify(postGradingHook, never()).onGraded(any());
	}

	private void assertPassDecision(
		String score,
		String ratio,
		boolean expected
	) {
		PreparedQuizSubmission prepared = prepared();
		QuizSubmitRequest request = new QuizSubmitRequest("request-1", List.of());
		GradingResult result = result(score);
		when(preparationService.prepare(1L, 50L, request)).thenReturn(prepared);
		when(gradingService.grade(prepared)).thenReturn(result);
		when(persistenceService.persist(1L, prepared, result, expected))
			.thenReturn(new PersistedQuizSubmission(
				response(result, expected),
				true
			));
		when(postGradingHook.onGraded(any()))
			.thenReturn(List.of(UiAction.moveNextPage()));

		assertThat(service(ratio).submit(1L, 50L, request).passed())
			.isEqualTo(expected);
	}

	private QuizSubmissionService service(String ratio) {
		return new QuizSubmissionService(
			preparationService,
			gradingService,
			persistenceService,
			new QuizProperties(new BigDecimal(ratio), 200),
			postGradingHook
		);
	}

	private PreparedQuizSubmission prepared() {
		return new PreparedQuizSubmission(
			50L,
			100L,
			10L,
			QuizType.MCQ,
			"1.0",
			"request-1",
			List.of(),
			List.of(),
			List.of(),
			null
		);
	}

	private GradingResult result(String score) {
		return new GradingResult(
			"1.0",
			new BigDecimal(score),
			new BigDecimal("100.00"),
			List.of()
		);
	}

	private QuizSubmitResponse response(
		GradingResult result,
		boolean passed
	) {
		return new QuizSubmitResponse(
			200L,
			50L,
			QuizType.MCQ,
			result.score(),
			result.maxScore(),
			passed,
			QuizGradingResultResponse.from(result),
			List.of(UiAction.moveNextPage())
		);
	}
}
