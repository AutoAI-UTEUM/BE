package io.edupilot.quiz;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
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

	public QuizSubmissionPersistenceService(
		LearningSessionRepository sessionRepository,
		QuizRepository quizRepository,
		QuizSubmissionRepository submissionRepository,
		UserRepository userRepository,
		UiActionResolver uiActionResolver
	) {
		this.sessionRepository = sessionRepository;
		this.quizRepository = quizRepository;
		this.submissionRepository = submissionRepository;
		this.userRepository = userRepository;
		this.uiActionResolver = uiActionResolver;
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
}
