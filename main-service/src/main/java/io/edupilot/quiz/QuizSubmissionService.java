package io.edupilot.quiz;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.TurnClaimService;
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
	private final TurnClaimService claimService;

	public QuizSubmissionService(
		QuizSubmissionPreparationService preparationService,
		QuizGradingService gradingService,
		QuizSubmissionPersistenceService persistenceService,
		QuizProperties properties,
		QuizPostGradingHook postGradingHook,
		TurnClaimService claimService
	) {
		this.preparationService = preparationService;
		this.gradingService = gradingService;
		this.persistenceService = persistenceService;
		this.properties = properties;
		this.postGradingHook = postGradingHook;
		this.claimService = claimService;
	}

	public QuizSubmitResponse submit(
		Long userId,
		Long quizId,
		QuizSubmitRequest request
	) {
		String requestId = request == null || request.requestId() == null
			? null
			: request.requestId().trim();
		Optional<QuizSubmitResponse> replay = persistenceService.findByRequest(
			userId,
			quizId,
			requestId
		);
		if (replay.isPresent()) {
			return replay.get();
		}
		if (persistenceService.exists(userId, quizId)) {
			throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
		}
		PreparedQuizSubmission prepared = preparationService.prepare(
			userId,
			quizId,
			request
		);
		String claimRequestId = quizClaimRequestId(prepared.requestId());
		try {
			claimService.claim(userId, prepared.sessionId(), claimRequestId);
		} catch (BusinessException exception) {
			if (exception.errorCode() == ErrorCode.TURN_IN_PROGRESS) {
				throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
			}
			throw exception;
		}
		try {
			replay = persistenceService.findByRequest(
				userId,
				quizId,
				prepared.requestId()
			);
			if (replay.isPresent()) {
				return replay.get();
			}
			if (persistenceService.exists(userId, quizId)) {
				throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
			}
			GradingResult gradingResult = gradingService.grade(prepared);
			boolean passed = gradingResult.score().compareTo(
				gradingResult.maxScore().multiply(properties.passRatio())
			) >= 0;
			PersistedQuizSubmission persisted = persistenceService.persist(
				userId,
				prepared,
				gradingResult,
				passed
			);
			QuizSubmitResponse response = persisted.response();
			if (!persisted.currentPageQuiz()) {
				return response;
			}
			List<UiAction> uiActions;
			try {
				uiActions = postGradingHook.onGraded(
					new QuizPostGradingContext(
						response.submissionId(),
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
						prepared.pageContext(),
						response.uiActions()
					)
				);
			} catch (RuntimeException exception) {
				log.atWarn()
					.addKeyValue(
						"submissionId",
						response.submissionId()
					)
					.addKeyValue("quizId", prepared.quizId())
					.addKeyValue(
						"failureType",
						exception.getClass().getSimpleName()
					)
					.log("Quiz learning-support pipeline failed");
				uiActions = response.uiActions();
			}
			return response.withUiActions(uiActions);
		} catch (DataIntegrityViolationException exception) {
			return persistenceService.findByRequest(
				userId,
				quizId,
				prepared.requestId()
			).orElseThrow(() ->
				new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED)
			);
		} finally {
			claimService.release(prepared.sessionId(), claimRequestId);
		}
	}

	private String quizClaimRequestId(String requestId) {
		UUID id = UUID.nameUUIDFromBytes(
			requestId.getBytes(StandardCharsets.UTF_8)
		);
		return "quiz:" + id;
	}
}
