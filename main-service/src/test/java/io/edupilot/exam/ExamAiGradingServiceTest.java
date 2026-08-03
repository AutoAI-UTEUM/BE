package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class ExamAiGradingServiceTest {

	@Mock private AiClient aiClient;
	@Mock private ExamSubmissionPersistenceService persistenceService;

	private ExamAiGradingService service;

	@BeforeEach
	void setUp() {
		service = new ExamAiGradingService(aiClient, persistenceService);
	}

	@Test
	void callsShortAndEssaySeparatelyAndPreservesSuccessWhenOtherGroupFails() {
		PreparedExamAiGrading prepared = prepared();
		when(persistenceService.prepareAiGrading(10L)).thenReturn(prepared);
		when(aiClient.grade(any()))
			.thenReturn(response("SHORT", "q1", "7.00"))
			.thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));
		ExamAiGradingOutcome outcome = service.grade(10L);

		ArgumentCaptor<GradeRequest> requestCaptor = ArgumentCaptor.forClass(
			GradeRequest.class
		);
		verify(aiClient, times(2)).grade(requestCaptor.capture());
		assertThat(requestCaptor.getAllValues()).extracting(GradeRequest::quizType)
			.containsExactly("SHORT", "ESSAY");
		assertThat(requestCaptor.getAllValues()).allSatisfy(request -> {
			assertThat(request.quizId()).isEqualTo(100L);
			assertThat(request.pageContext()).isNull();
			assertThat(request.learnerMemoryDigest()).isNull();
			assertThat(request.items()).isNotEmpty();
			assertThat(request.studentAnswers()).isNotEmpty();
		});

		assertThat(outcome.failed()).isTrue();
		assertThat(outcome.grades()).containsKey("q1");
		assertThat(outcome.grades()).doesNotContainKey("q2");
	}

	@Test
	void marksAiRequestInvalidFailedAfterStillCallingRemainingGroup() {
		when(persistenceService.prepareAiGrading(10L)).thenReturn(prepared());
		when(aiClient.grade(any()))
			.thenThrow(new AiClientException(
				ErrorCode.AI_RESPONSE_INVALID,
				AiFailureCategory.SCHEMA,
				false,
				"AI_REQUEST_INVALID",
				null
			))
			.thenReturn(response("ESSAY", "q2", "8.00"));

		ExamAiGradingOutcome outcome = service.grade(10L);

		verify(aiClient, times(2)).grade(any());
		assertThat(outcome.failed()).isTrue();
		assertThat(outcome.grades()).containsKey("q2");
	}

	private PreparedExamAiGrading prepared() {
		List<GradeRequest.Rubric> rubric = List.of(
			new GradeRequest.Rubric("Accuracy", BigDecimal.ONE)
		);
		return new PreparedExamAiGrading(
			10L,
			100L,
			List.of(
				new PreparedExamAiGrading.Group(
					ExamQuestionType.SHORT,
					List.of(new PreparedExamAiGrading.Item(
						"q1", "Short question", "Reference", rubric,
						new BigDecimal("10.00"), "Short answer"
					))
				),
				new PreparedExamAiGrading.Group(
					ExamQuestionType.ESSAY,
					List.of(new PreparedExamAiGrading.Item(
						"q2", "Essay question", "Model", rubric,
						new BigDecimal("10.00"), "Essay answer"
					))
				)
			)
		);
	}

	private GradeResponse response(String type, String questionId, String score) {
		return new GradeResponse(
			"1.0",
			100L,
			type,
			new BigDecimal(score),
			new BigDecimal("10.00"),
			List.of(new GradeResponse.Item(
				questionId,
				new BigDecimal(score),
				new BigDecimal("10.00"),
				"PARTIAL",
				"Feedback"
			)),
			null
		);
	}
}
