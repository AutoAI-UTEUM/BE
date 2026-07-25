package io.edupilot.quiz;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmitResponse;

@Service
public class QuizSubmissionService {

	private final QuizSubmissionPreparationService preparationService;
	private final QuizGradingService gradingService;
	private final QuizSubmissionPersistenceService persistenceService;
	private final QuizProperties properties;

	public QuizSubmissionService(
		QuizSubmissionPreparationService preparationService,
		QuizGradingService gradingService,
		QuizSubmissionPersistenceService persistenceService,
		QuizProperties properties
	) {
		this.preparationService = preparationService;
		this.gradingService = gradingService;
		this.persistenceService = persistenceService;
		this.properties = properties;
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
			return persistenceService.persist(
				userId,
				prepared,
				gradingResult,
				passed
			);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
		}
	}
}
