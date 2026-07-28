package io.edupilot.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.QuizAssessmentRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.diagnosis.DiagnosisPersistenceService;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.quiz.GradingItem;
import io.edupilot.quiz.GradingResult;
import io.edupilot.quiz.GradingVerdict;
import io.edupilot.quiz.PrivateQuizQuestion;
import io.edupilot.quiz.PublicQuizQuestion;
import io.edupilot.quiz.QuizPostGradingContext;
import io.edupilot.quiz.QuizType;
import io.edupilot.quiz.SubmittedAnswer;
import io.edupilot.session.UiAction;

@ExtendWith(MockitoExtension.class)
class LearningSupportPipelineTest {

	@Mock
	private AiClient aiClient;

	@Mock
	private AssessmentPersistenceService assessmentPersistenceService;

	@Mock
	private DiagnosisPersistenceService diagnosisPersistenceService;

	@Mock
	private LearnerMemoryRepository memoryRepository;

	@Test
	void passedSubmissionStoresAssessmentAndNeverCallsDiagnosis() {
		QuizPostGradingContext context = context(true);
		QuizAssessmentResponse response = assessmentResponse();
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(aiClient.quizAssessment(any())).thenReturn(response);
		when(assessmentPersistenceService.save(context, response))
			.thenReturn(
				new AssessmentPersistenceService.AssessmentSaveResult(
					true,
					300L
				)
			);

		List<UiAction> actions = pipeline().onGraded(context);

		assertThat(actions).containsExactly(UiAction.moveNextPage());
		verify(aiClient, never()).diagnosis(any());
		verify(diagnosisPersistenceService, never())
			.savePending(any(), any());
	}

	@Test
	void failedSubmissionCallsDiagnosisAndReturnsQuestionReference() {
		QuizPostGradingContext context = context(false);
		QuizAssessmentResponse assessment = assessmentResponse();
		DiagnosisResponse diagnosis = diagnosisResponse();
		UiAction action = UiAction.diagnosisQuestion("막힌 지점은?", 30L);
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(aiClient.quizAssessment(any())).thenReturn(assessment);
		when(assessmentPersistenceService.save(context, assessment))
			.thenReturn(
				new AssessmentPersistenceService.AssessmentSaveResult(
					true,
					300L
				)
			);
		when(aiClient.diagnosis(any())).thenReturn(diagnosis);
		when(diagnosisPersistenceService.savePending(context, diagnosis))
			.thenReturn(Optional.of(action));

		List<UiAction> actions = pipeline().onGraded(context);

		assertThat(actions).containsExactly(action);
		ArgumentCaptor<io.edupilot.ai.dto.DiagnosisRequest> request =
			ArgumentCaptor.forClass(
				io.edupilot.ai.dto.DiagnosisRequest.class
			);
		verify(aiClient).diagnosis(request.capture());
		assertThat(request.getValue().wrongItems())
			.singleElement()
			.extracting(
				io.edupilot.ai.dto.DiagnosisRequest.WrongItem::questionId
			)
			.isEqualTo("q1");
	}

	@Test
	void assessmentFailureKeepsDefaultActionAndSkipsDiagnosis() {
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(aiClient.quizAssessment(any()))
			.thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));

		List<UiAction> actions = pipeline().onGraded(context(false));

		assertThat(actions).containsExactly(UiAction.moveNextPage());
		verify(aiClient, never()).diagnosis(any());
	}

	@Test
	void diagnosisFailureKeepsStoredAssessmentAndDefaultAction() {
		QuizPostGradingContext context = context(false);
		QuizAssessmentResponse assessment = assessmentResponse();
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(aiClient.quizAssessment(any())).thenReturn(assessment);
		when(assessmentPersistenceService.save(context, assessment))
			.thenReturn(
				new AssessmentPersistenceService.AssessmentSaveResult(
					true,
					300L
				)
			);
		when(aiClient.diagnosis(any()))
			.thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));

		assertThat(pipeline().onGraded(context))
			.containsExactly(UiAction.moveNextPage());
		verify(assessmentPersistenceService).save(context, assessment);
	}

	@Test
	void discardedAssessmentAfterSessionChangeSkipsDiagnosis() {
		QuizPostGradingContext context = context(false);
		QuizAssessmentResponse response = assessmentResponse();
		when(memoryRepository.findByUser_IdAndMaterial_Id(1L, 10L))
			.thenReturn(Optional.empty());
		when(aiClient.quizAssessment(any())).thenReturn(response);
		when(assessmentPersistenceService.save(context, response))
			.thenReturn(
				new AssessmentPersistenceService.AssessmentSaveResult(
					false,
					null
				)
			);

		assertThat(pipeline().onGraded(context))
			.containsExactly(UiAction.moveNextPage());
		verify(aiClient, never()).diagnosis(any());
	}

	private LearningSupportPipeline pipeline() {
		return new LearningSupportPipeline(
			aiClient,
			assessmentPersistenceService,
			diagnosisPersistenceService,
			memoryRepository
		);
	}

	private QuizPostGradingContext context(boolean passed) {
		PublicQuizQuestion publicQuestion = new PublicQuizQuestion(
			"q1",
			"분수 나눗셈의 의미는?",
			BigDecimal.TEN,
			null
		);
		PrivateQuizQuestion privateQuestion = new PrivateQuizQuestion(
			"q1",
			null,
			null,
			null,
			"역수의 곱셈",
			List.of(),
			List.of(),
			null
		);
		BigDecimal score = passed
			? BigDecimal.TEN
			: new BigDecimal("4.00");
		GradingVerdict verdict = passed
			? GradingVerdict.CORRECT
			: GradingVerdict.WRONG;
		return new QuizPostGradingContext(
			200L,
			50L,
			100L,
			1L,
			10L,
			QuizType.SHORT,
			"1.0",
			List.of(publicQuestion),
			List.of(privateQuestion),
			List.of(new SubmittedAnswer("q1", "학생 답안")),
			new GradingResult(
				"1.0",
				score,
				BigDecimal.TEN,
				List.of(new GradingItem(
					"q1",
					score,
					BigDecimal.TEN,
					verdict,
					"피드백"
				))
			),
			passed,
			new GradeRequest.PageContext(1, 1, "페이지 문맥"),
			List.of(UiAction.moveNextPage())
		);
	}

	private QuizAssessmentResponse assessmentResponse() {
		return new QuizAssessmentResponse(
			"1.0",
			"이해 요약",
			List.of(),
			List.of("약점"),
			List.of("오개념 후보"),
			"REVIEW",
			List.of(),
			List.of("q1"),
			null
		);
	}

	private DiagnosisResponse diagnosisResponse() {
		return new DiagnosisResponse(
			"1.0",
			List.of("역수"),
			List.of("절차 암기"),
			"막힌 지점은?",
			List.of("q1"),
			"나눗셈과 연결",
			null
		);
	}
}
