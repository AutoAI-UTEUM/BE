package io.edupilot.quiz;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmissionDetailResponse;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.TurnClaimService;
import io.edupilot.session.UiAction;

@Service
public class QuizSubmissionService {

	private final GuardianConsentFence consentFence;

	private static final Logger log =
		LoggerFactory.getLogger(QuizSubmissionService.class);

	private final QuizSubmissionPreparationService preparationService;
	private final QuizGradingService gradingService;
	private final QuizSubmissionPersistenceService persistenceService;
	private final QuizProperties properties;
	private final QuizPostGradingHook postGradingHook;
	private final TurnClaimService claimService;
	private final MaterialAccessService materialAccessService;

	public QuizSubmissionService(
		QuizSubmissionPreparationService preparationService,
		QuizGradingService gradingService,
		QuizSubmissionPersistenceService persistenceService,
		QuizProperties properties,
		QuizPostGradingHook postGradingHook,
		TurnClaimService claimService,
		MaterialAccessService materialAccessService,
		GuardianConsentFence consentFence
	) {
		this.consentFence = consentFence;
		this.preparationService = preparationService;
		this.gradingService = gradingService;
		this.persistenceService = persistenceService;
		this.properties = properties;
		this.postGradingHook = postGradingHook;
		this.claimService = claimService;
		this.materialAccessService = materialAccessService;
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
			materialAccessService.assertSessionAccessible(userId, prepared.sessionId());
			GuardianConsentFence.Snapshot consent = prepared.quizType().usesAiGrading()
				? consentFence.capture(userId) : null;
			GradingResult gradingResult = consent == null ? gradingService.grade(userId, prepared)
				: gradingService.grade(userId, prepared, consent);
			boolean passed = gradingResult.score().compareTo(
				gradingResult.maxScore().multiply(properties.passRatio())
			) >= 0;
			PersistedQuizSubmission persisted = consent == null
				? persistenceService.persist(userId, prepared, gradingResult, passed)
				: consentFence.complete(consent,
					() -> persistenceService.persist(userId, prepared, gradingResult, passed));
			QuizSubmitResponse response = persisted.response();
			materialAccessService.assertSessionAccessible(userId, prepared.sessionId());
			if (!persisted.currentPageQuiz()) {
				if (consent != null) consentFence.assertCurrent(consent);
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
						response.uiActions(),
						consent
					)
				);
			} catch (RuntimeException exception) {
				if (exception instanceof BusinessException businessException
					&& (businessException.errorCode() == ErrorCode.AI_QUOTA_EXCEEDED
						|| businessException.errorCode() == ErrorCode.MATERIAL_NOT_FOUND
						|| businessException.errorCode() == ErrorCode.SESSION_NOT_FOUND
						|| GuardianConsentFence.isConsentFailure(businessException))) {
					throw businessException;
				}
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
			materialAccessService.assertSessionAccessible(userId, prepared.sessionId());
			if (consent != null) consentFence.assertCurrent(consent);
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

	public QuizSubmissionDetailResponse detail(Long userId, Long quizId) {
		return persistenceService.findDetail(userId, quizId)
			.orElseThrow(() ->
				new BusinessException(ErrorCode.QUIZ_NOT_FOUND)
			);
	}

	private String quizClaimRequestId(String requestId) {
		UUID id = UUID.nameUUIDFromBytes(
			requestId.getBytes(StandardCharsets.UTF_8)
		);
		return "quiz:" + id;
	}
}
