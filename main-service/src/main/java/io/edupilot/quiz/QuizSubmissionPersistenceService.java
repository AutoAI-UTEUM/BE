package io.edupilot.quiz;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.diagnosis.Diagnosis;
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.diagnosis.DiagnosisStatus;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.quiz.dto.QuizSubmissionDetailResponse;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.UiAction;
import io.edupilot.session.UiActionResolver;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class QuizSubmissionPersistenceService {

	private final LearningSessionRepository sessionRepository;
	private final QuizRepository quizRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final UserRepository userRepository;
	private final UiActionResolver uiActionResolver;
	private final DiagnosisRepository diagnosisRepository;
	private final MaterialAccessService materialAccessService;

	public QuizSubmissionPersistenceService(
		LearningSessionRepository sessionRepository,
		QuizRepository quizRepository,
		QuizSubmissionRepository submissionRepository,
		UserRepository userRepository,
		UiActionResolver uiActionResolver,
		DiagnosisRepository diagnosisRepository,
		MaterialAccessService materialAccessService
	) {
		this.sessionRepository = sessionRepository;
		this.quizRepository = quizRepository;
		this.submissionRepository = submissionRepository;
		this.userRepository = userRepository;
		this.uiActionResolver = uiActionResolver;
		this.diagnosisRepository = diagnosisRepository;
		this.materialAccessService = materialAccessService;
	}

	@Transactional(readOnly = true)
	public Optional<QuizSubmitResponse> findByRequest(
		Long userId,
		Long quizId,
		String requestId
	) {
		if (requestId == null || requestId.isBlank()) {
			return Optional.empty();
		}
		return submissionRepository.findByRequest(
			quizId,
			userId,
			requestId
		).map(submission -> reconstruct(submission).toSubmitResponse(
			replayUiActions(submission)
		));
	}

	@Transactional(readOnly = true)
	public Optional<QuizSubmissionDetailResponse> findDetail(
		Long userId,
		Long quizId
	) {
		return submissionRepository.findOwnedByQuizId(quizId, userId)
			.map(submission -> {
				materialAccessService.assertSessionAccessible(
					userId,
					submission.getSessionId()
				);
				return reconstruct(submission).toDetailResponse();
			});
	}

	@Transactional(readOnly = true)
	public boolean exists(Long userId, Long quizId) {
		return submissionRepository.existsByQuiz_IdAndUser_Id(quizId, userId);
	}

	@Transactional
	public PersistedQuizSubmission persist(
		Long userId,
		PreparedQuizSubmission prepared,
		GradingResult gradingResult,
		boolean passed
	) {
		LearningSession session = sessionRepository.findOwnedForUpdate(
				prepared.sessionId(),
				userId
			)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.QUIZ_NOT_SUBMITTABLE);
		}
		Quiz quiz = quizRepository.findByIdAndSessionId(
				prepared.quizId(),
				session.getId()
			)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		if (!Objects.equals(session.getActiveQuizId(), quiz.getId())) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}
		if (submissionRepository.existsByQuiz_IdAndUser_Id(
			quiz.getId(),
			userId
		)) {
			throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
		}

		User user = userRepository.getReferenceById(userId);
		QuizSubmission submission = submissionRepository.saveAndFlush(
			QuizSubmission.create(
				quiz,
				user,
				prepared.requestId(),
				prepared.answers(),
				gradingResult,
				passed
			)
		);
		boolean currentPageQuiz = quiz.getPageNumber()
			== session.getCurrentPage();
		List<UiAction> uiActions = currentPageQuiz
			? uiActionResolver.nextLearning(
				session.getCurrentPage(),
				session.getMaterialPageCount()
			)
			: session.getLastUiActions();
		session.completeQuizSubmission(
			quiz.getId(),
			uiActions,
			passed,
			currentPageQuiz
		);
		sessionRepository.flush();

		return new PersistedQuizSubmission(
			QuizSubmitResponse.from(submission, uiActions),
			currentPageQuiz
		);
	}

	private List<UiAction> replayUiActions(QuizSubmission submission) {
		Diagnosis diagnosis = diagnosisRepository
			.findBySubmission_Id(submission.getId())
			.orElse(null);
		if (diagnosis != null
			&& diagnosis.getStatus() != DiagnosisStatus.COMPLETED
			&& Objects.equals(
				diagnosis.getId(),
				submission.getSessionPendingDiagnosisId()
			)) {
			return List.of(UiAction.diagnosisQuestion(
				diagnosis.getDiagnosticPrompt(),
				diagnosis.getId()
			));
		}
		return submission.getSessionUiActions();
	}

	private QuizSubmissionReconstruction reconstruct(
		QuizSubmission submission
	) {
		return QuizSubmissionReconstruction.from(submission);
	}
}
