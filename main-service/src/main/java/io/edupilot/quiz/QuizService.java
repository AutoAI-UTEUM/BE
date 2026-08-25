package io.edupilot.quiz;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizDetailResponse;
import io.edupilot.quiz.dto.QuizListResponse;
import io.edupilot.quiz.dto.QuizSummaryResponse;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;

@Service
public class QuizService {

	private static final int QUIZ_LIST_LIMIT = 100;

	private final QuizRepository quizRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final LearningSessionRepository sessionRepository;
	private final QuizGenerationValidator generationValidator;

	public QuizService(
		QuizRepository quizRepository,
		QuizSubmissionRepository submissionRepository,
		LearningSessionRepository sessionRepository,
		QuizGenerationValidator generationValidator
	) {
		this.quizRepository = quizRepository;
		this.submissionRepository = submissionRepository;
		this.sessionRepository = sessionRepository;
		this.generationValidator = generationValidator;
	}

	@Transactional
	public Long createFromGeneration(
		Long sessionId,
		String schemaVersion,
		QuizGeneration generation
	) {
		LearningSession session = sessionRepository.findById(sessionId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		Integer materialPageCount = session.getMaterialPageCount();
		if (materialPageCount == null) {
			throw invalidGeneration();
		}
		int page = session.getCurrentPage();
		generationValidator.validate(
			generation,
			schemaVersion,
			null,
			snapshotPages(page, materialPageCount)
		);
		QuizType quizType = QuizType.valueOf(generation.quizType());
		List<PublicQuizQuestion> publicQuestions = generation.questions()
			.stream()
			.map(question -> publicQuestion(question, quizType))
			.toList();
		List<PrivateQuizQuestion> privateQuestions = generation.questions()
			.stream()
			.map(question -> privateQuestion(question, quizType))
			.toList();

		Quiz quiz = quizRepository.saveAndFlush(Quiz.create(
			session,
			page,
			generation.title().trim(),
			generation.coverage().startPage(),
			generation.coverage().endPage(),
			quizType,
			publicQuestions,
			privateQuestions,
			schemaVersion
		));
		return quiz.getId();
	}

	@Transactional(readOnly = true)
	public QuizDetailResponse detail(Long userId, Long quizId) {
		Quiz quiz = ownedVisibleQuiz(userId, quizId);
		boolean submitted = submissionRepository.existsByQuiz_IdAndUser_Id(
			quizId,
			userId
		);
		return QuizDetailResponse.from(quiz, submitted);
	}

	@Transactional(readOnly = true)
	public QuizListResponse list(Long userId, Long sessionId) {
		sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.filter(session -> session.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

		List<Quiz> quizzes = quizRepository
			.findBySession_IdOrderByCreatedAtDescIdDesc(
				sessionId,
				PageRequest.of(0, QUIZ_LIST_LIMIT)
			);
		if (quizzes.isEmpty()) {
			return new QuizListResponse(List.of());
		}

		List<Long> quizIds = quizzes.stream().map(Quiz::getId).toList();
		Map<Long, QuizSubmission> submissions = new HashMap<>();
		for (QuizSubmission submission :
			submissionRepository.findByQuiz_IdInAndUser_Id(quizIds, userId)) {
			submissions.put(submission.getQuizId(), submission);
		}

		return new QuizListResponse(quizzes.stream()
			.map(quiz -> QuizSummaryResponse.from(
				quiz,
				submissions.get(quiz.getId())
			))
			.toList());
	}

	private Quiz ownedVisibleQuiz(Long userId, Long quizId) {
		return quizRepository.findOwned(quizId, userId)
			.filter(quiz -> quiz.getSessionStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
	}

	private PublicQuizQuestion publicQuestion(
		QuizGeneration.Question question,
		QuizType quizType
	) {
		List<QuizOption> options = quizType == QuizType.MCQ
			? question.choices().stream()
				.map(choice -> new QuizOption(
					choice.choiceId().trim(),
					choice.text().trim()
				))
				.toList()
			: null;
		return new PublicQuizQuestion(
			question.questionId().trim(),
			question.questionText().trim(),
			question.points(),
			options
		);
	}

	private PrivateQuizQuestion privateQuestion(
		QuizGeneration.Question question,
		QuizType quizType
	) {
		String questionId = question.questionId().trim();
		return switch (quizType) {
			case MCQ -> new PrivateQuizQuestion(
				questionId,
				question.answerChoiceId().trim(),
				null,
				question.explanation().trim(),
				null,
				null,
				null,
				null
			);
			case OX -> new PrivateQuizQuestion(
				questionId,
				null,
				question.answerValue(),
				question.explanation().trim(),
				null,
				null,
				null,
				null
			);
			case SHORT -> new PrivateQuizQuestion(
				questionId,
				null,
				null,
				null,
				question.referenceAnswer().trim(),
				question.gradingCriteria().stream()
					.map(String::trim)
					.toList(),
				null,
				null
			);
			case ESSAY -> new PrivateQuizQuestion(
				questionId,
				null,
				null,
				null,
				null,
				null,
				question.rubric().stream()
					.map(value -> new RubricCriterion(
						value.criterion().trim(),
						value.weight()
					))
					.toList(),
				question.modelAnswer().trim()
			);
		};
	}

	private Set<Integer> snapshotPages(int currentPage, int pageCount) {
		Set<Integer> pages = new java.util.LinkedHashSet<>();
		if (currentPage > 1) {
			pages.add(currentPage - 1);
		}
		pages.add(currentPage);
		if (currentPage < pageCount) {
			pages.add(currentPage + 1);
		}
		return Set.copyOf(pages);
	}

	private BusinessException invalidGeneration() {
		return new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}
}
