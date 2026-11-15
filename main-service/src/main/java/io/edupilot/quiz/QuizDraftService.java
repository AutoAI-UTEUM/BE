package io.edupilot.quiz;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.quiz.dto.QuizDraftResponse;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.SaveQuizDraftRequest;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class QuizDraftService {
	private static final int MAX_DRAFT_BYTES = 256 * 1024;
	private static final TypeReference<List<QuizSubmitRequest.Answer>> ANSWERS_TYPE =
		new TypeReference<>() {};
	private final QuizRepository quizzes;
	private final QuizSubmissionRepository submissions;
	private final LearningSessionRepository sessions;
	private final MaterialAccessService access;
	private final QuizDraftRateLimiter limiter;
	private final ObjectMapper mapper;
	private final Clock clock;

	public QuizDraftService(QuizRepository quizzes, QuizSubmissionRepository submissions,
		LearningSessionRepository sessions, MaterialAccessService access,
		QuizDraftRateLimiter limiter, ObjectMapper mapper, Clock clock) {
		this.quizzes = quizzes;
		this.submissions = submissions;
		this.sessions = sessions;
		this.access = access;
		this.limiter = limiter;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public QuizDraftResponse save(Long userId, Long quizId, SaveQuizDraftRequest request) {
		Quiz quiz = requireAccessibleLocked(userId, quizId);
		if (quiz.getSessionStatus() != SessionStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.QUIZ_NOT_SUBMITTABLE);
		}
		if (submissions.existsByQuiz_IdAndUser_Id(quizId, userId)) {
			throw new BusinessException(ErrorCode.QUIZ_ALREADY_SUBMITTED);
		}
		if (!Objects.equals(quiz.getSessionActiveQuizId(), quizId)) {
			throw new BusinessException(ErrorCode.SESSION_STATE_CONFLICT);
		}
		if (!limiter.allow(userId, quizId)) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
		if (request == null || request.answers() == null
			|| request.version() != null && request.version() < 0) {
			throw new BusinessException(ErrorCode.INVALID_QUIZ_ANSWER);
		}
		if (mapper.valueToTree(request).toString().getBytes(StandardCharsets.UTF_8).length
			> MAX_DRAFT_BYTES) {
			throw new BusinessException(ErrorCode.DRAFT_TOO_LARGE);
		}
		List<QuizSubmitRequest.Answer> answers = validate(quiz, request.answers());
		int version = request.version() == null ? 0 : request.version();
		if (version != quiz.draftVersion() || version == Integer.MAX_VALUE) {
			throw new QuizDraftVersionConflictException(response(quiz));
		}
		quiz.saveDraft(mapper.writeValueAsString(answers), clock.instant());
		quizzes.flush();
		return response(quiz);
	}

	// Use the same session -> material/grants lock order as final submission.
	// Locking even a missing draft prevents two first PUTs or a PUT after submission.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public QuizDraftResponse get(Long userId, Long quizId) {
		return response(requireAccessibleLocked(userId, quizId));
	}

	private Quiz requireAccessibleLocked(Long userId, Long quizId) {
		Long sessionId = quizzes.findOwnedSessionId(quizId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		LearningSession session = sessions.findOwnedForUpdate(sessionId, userId)
			.filter(value -> value.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		access.requireAccessibleForUpdate(userId, session.getMaterialId());
		return quizzes.findByIdAndSessionId(quizId, sessionId)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
	}

	private List<QuizSubmitRequest.Answer> validate(Quiz quiz, List<QuizSubmitRequest.Answer> requested) {
		Set<String> validIds = new HashSet<>();
		quiz.getPublicQuestions().forEach(question -> validIds.add(question.questionId()));
		Set<String> seen = new HashSet<>();
		List<QuizSubmitRequest.Answer> answers = new ArrayList<>();
		for (var item : requested) {
			if (item == null || item.questionId() == null) {
				throw new BusinessException(ErrorCode.INVALID_QUIZ_ANSWER);
			}
			String id = item.questionId().trim();
			if (!validIds.contains(id) || !seen.add(id)) {
				throw new BusinessException(ErrorCode.INVALID_QUIZ_ANSWER);
			}
			// Drafts accept incomplete answers; final submission keeps its own validation.
			answers.add(new QuizSubmitRequest.Answer(id,
				item.answer() == null ? null : item.answer().deepCopy()));
		}
		return List.copyOf(answers);
	}

	private QuizDraftResponse response(Quiz quiz) {
		// Keep the Spring JSON tree type at the API boundary, not in Hibernate's JSON mapper.
		if (quiz.draftAnswersJson() == null) {
			return null;
		}
		var answers = mapper.readValue(quiz.draftAnswersJson(), ANSWERS_TYPE).stream()
			.map(answer -> answer.answer() != null && answer.answer().isNull()
				? new QuizSubmitRequest.Answer(answer.questionId(), null) : answer)
			.toList();
		return new QuizDraftResponse(quiz.draftVersion(), answers, quiz.draftSavedAt());
	}
}
