package io.edupilot.quiz;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import io.edupilot.session.UiAction;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class QuizSubmissionPersistenceService {

	private final LearningSessionRepository sessionRepository;
	private final QuizRepository quizRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final UserRepository userRepository;
	private final ApplicationEventPublisher eventPublisher;

	public QuizSubmissionPersistenceService(
		LearningSessionRepository sessionRepository,
		QuizRepository quizRepository,
		QuizSubmissionRepository submissionRepository,
		UserRepository userRepository,
		ApplicationEventPublisher eventPublisher
	) {
		this.sessionRepository = sessionRepository;
		this.quizRepository = quizRepository;
		this.submissionRepository = submissionRepository;
		this.userRepository = userRepository;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	public QuizSubmitResponse persist(
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
		List<UiAction> uiActions = List.of(UiAction.moveNextPage());
		session.completeQuizSubmission(quiz.getId(), uiActions);
		sessionRepository.flush();

		eventPublisher.publishEvent(
			new QuizGradedEvent(QuizSubmissionSnapshot.from(submission))
		);
		// TODO Epic7: 미달 시 진단 파이프라인과 DIAGNOSIS_QUESTION으로 교체한다.
		return QuizSubmitResponse.from(submission, uiActions);
	}
}
