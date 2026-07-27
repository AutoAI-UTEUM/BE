package io.edupilot.quiz;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.UiAction;

@Service
public class QuizSubmissionService {

	private static final Logger log =
		LoggerFactory.getLogger(QuizSubmissionService.class);

	private final QuizSubmissionPreparationService preparationService;
	private final QuizGradingService gradingService;
	private final QuizSubmissionPersistenceService persistenceService;
	private final QuizProperties properties;
	private final QuizPostGradingHook postGradingHook;

	public QuizSubmissionService(
		QuizSubmissionPreparationService preparationService,
		QuizGradingService gradingService,
		QuizSubmissionPersistenceService persistenceService,
		QuizProperties properties,
		QuizPostGradingHook postGradingHook
	) {
		this.preparationService = preparationService;
		this.gradingService = gradingService;
		this.persistenceService = persistenceService;
		this.properties = properties;
		this.postGradingHook = postGradingHook;
	}

	public QuizSubmitResponse submit(
		Long userId,
		Long quizId,
		QuizSubmitRequest request
	) {
		PreparedQuizSubmission prepared = preparationService.prepare(
			userId,
			quizId,
			request
		);
		GradingResult gradingResult = gradingService.grade(prepared);
		boolean passed = gradingResult.score().compareTo(
			gradingResult.maxScore().multiply(properties.passRatio())
		) >= 0;
		try {
			QuizSubmitResponse persisted = persistenceService.persist(
				userId,
				prepared,
				gradingResult,
				passed
			);
			List<UiAction> uiActions;
			try {
				uiActions = postGradingHook.onGraded(
					new QuizPostGradingContext(
						persisted.submissionId(),
						prepared.quizId(),
						prepared.sessionId(),
						userId,
						prepared.materialId(),
						prepared.quizType(),
						prepared.schemaVersion(),
						prepared.publicQuestions(),
						prepared.privateQuestions(),
						prepared.answers(),
						gradingResult,
						passed,
						prepared.pageContext()
					)
				);
			} catch (RuntimeException exception) {
				log.warn(
					"Quiz learning-support pipeline failed: submissionId={}, quizId={}, failureType={}",
					persisted.submissionId(),
					prepared.quizId(),
					exception.getClass().getSimpleName()
				);
				uiActions = List.of(UiAction.moveNextPage());
			}
			return persisted.withUiActions(uiActions);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
		}
	}
}
