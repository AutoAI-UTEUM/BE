package io.edupilot.quiz;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.quiz.dto.QuizDetailResponse;
import io.edupilot.quiz.dto.QuizListResponse;
import io.edupilot.quiz.dto.QuizSummaryResponse;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.SessionStatus;
import tools.jackson.databind.JsonNode;

@Service
public class QuizService {

	private static final String SCHEMA_VERSION = "1.0";
	private static final int MIN_QUESTION_COUNT = 5;
	private static final int MAX_QUESTION_COUNT = 10;
	private static final int QUIZ_LIST_LIMIT = 100;
	private static final BigDecimal RUBRIC_WEIGHT_TOLERANCE =
		new BigDecimal("0.001");

	private final QuizRepository quizRepository;
	private final QuizSubmissionRepository submissionRepository;
	private final LearningSessionRepository sessionRepository;

	public QuizService(
		QuizRepository quizRepository,
		QuizSubmissionRepository submissionRepository,
		LearningSessionRepository sessionRepository
	) {
		this.quizRepository = quizRepository;
		this.submissionRepository = submissionRepository;
		this.sessionRepository = sessionRepository;
	}

	@Transactional
	public Long createFromGeneration(Long sessionId, JsonNode generation) {
		LearningSession session = sessionRepository.findById(sessionId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		Integer materialPageCount = session.getMaterialPageCount();
		if (materialPageCount == null) {
			throw invalidGeneration();
		}

		String schemaVersion = requiredText(generation, "schemaVersion");
		if (!SCHEMA_VERSION.equals(schemaVersion)) {
			throw invalidGeneration();
		}
		requiredText(generation, "generationId");
		QuizType quizType = requiredQuizType(generation);
		int page = requiredPositiveInt(generation, "page");
		int coverageStartPage = requiredPositiveInt(
			generation,
			"coverageStartPage"
		);
		int coverageEndPage = requiredPositiveInt(
			generation,
			"coverageEndPage"
		);
		String title = requiredText(generation, "title");
		if (title.length() > 255) {
			throw invalidGeneration();
		}
		int questionCount = requiredPositiveInt(generation, "questionCount");
		JsonNode questionsNode = requiredArray(generation, "questions");

		if (page > materialPageCount
			|| coverageStartPage > coverageEndPage
			|| coverageEndPage > materialPageCount
			|| questionCount < MIN_QUESTION_COUNT
			|| questionCount > MAX_QUESTION_COUNT
			|| questionsNode.size() != questionCount) {
			throw invalidGeneration();
		}

		List<PublicQuizQuestion> publicQuestions = new ArrayList<>();
		List<PrivateQuizQuestion> privateQuestions = new ArrayList<>();
		Set<String> questionIds = new HashSet<>();
		for (JsonNode questionNode : questionsNode) {
			ParsedQuestion parsed = parseQuestion(questionNode, quizType);
			if (!questionIds.add(parsed.publicQuestion().questionId())) {
				throw invalidGeneration();
			}
			publicQuestions.add(parsed.publicQuestion());
			privateQuestions.add(parsed.privateQuestion());
		}

		Quiz quiz = quizRepository.saveAndFlush(Quiz.create(
			session,
			page,
			title,
			coverageStartPage,
			coverageEndPage,
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

	private ParsedQuestion parseQuestion(
		JsonNode questionNode,
		QuizType quizType
	) {
		if (questionNode == null || !questionNode.isObject()) {
			throw invalidGeneration();
		}
		String questionId = requiredText(questionNode, "questionId");
		String questionText = requiredText(questionNode, "questionText");
		BigDecimal maxScore = requiredPositiveDecimal(questionNode, "maxScore");

		return switch (quizType) {
			case MCQ -> parseMcq(
				questionNode,
				questionId,
				questionText,
				maxScore
			);
			case OX -> parseOx(
				questionNode,
				questionId,
				questionText,
				maxScore
			);
			case SHORT -> parseShort(
				questionNode,
				questionId,
				questionText,
				maxScore
			);
			case ESSAY -> parseEssay(
				questionNode,
				questionId,
				questionText,
				maxScore
			);
		};
	}

	private ParsedQuestion parseMcq(
		JsonNode questionNode,
		String questionId,
		String questionText,
		BigDecimal maxScore
	) {
		JsonNode optionsNode = requiredArray(questionNode, "options");
		if (optionsNode.size() == 0) {
			throw invalidGeneration();
		}
		List<QuizOption> options = new ArrayList<>();
		Set<String> optionIds = new HashSet<>();
		for (JsonNode optionNode : optionsNode) {
			String optionId = requiredText(optionNode, "optionId");
			String text = requiredText(optionNode, "text");
			if (!optionIds.add(optionId)) {
				throw invalidGeneration();
			}
			options.add(new QuizOption(optionId, text));
		}
		String correctOptionId = requiredText(questionNode, "correctOptionId");
		if (!optionIds.contains(correctOptionId)) {
			throw invalidGeneration();
		}
		String explanation = requiredText(questionNode, "explanation");
		return new ParsedQuestion(
			new PublicQuizQuestion(
				questionId,
				questionText,
				maxScore,
				List.copyOf(options)
			),
			new PrivateQuizQuestion(
				questionId,
				correctOptionId,
				null,
				explanation,
				null,
				null,
				null,
				null
			)
		);
	}

	private ParsedQuestion parseOx(
		JsonNode questionNode,
		String questionId,
		String questionText,
		BigDecimal maxScore
	) {
		JsonNode correctAnswer = questionNode.get("correctAnswer");
		if (correctAnswer == null || !correctAnswer.isBoolean()) {
			throw invalidGeneration();
		}
		String explanation = requiredText(questionNode, "explanation");
		return new ParsedQuestion(
			new PublicQuizQuestion(questionId, questionText, maxScore, null),
			new PrivateQuizQuestion(
				questionId,
				null,
				correctAnswer.booleanValue(),
				explanation,
				null,
				null,
				null,
				null
			)
		);
	}

	private ParsedQuestion parseShort(
		JsonNode questionNode,
		String questionId,
		String questionText,
		BigDecimal maxScore
	) {
		String referenceAnswer = requiredText(questionNode, "referenceAnswer");
		List<String> keywords = parseTextArray(
			requiredArray(questionNode, "acceptableKeywords")
		);
		List<RubricCriterion> rubric = parseRubric(
			requiredArray(questionNode, "rubric")
		);
		return new ParsedQuestion(
			new PublicQuizQuestion(questionId, questionText, maxScore, null),
			new PrivateQuizQuestion(
				questionId,
				null,
				null,
				null,
				referenceAnswer,
				keywords,
				rubric,
				null
			)
		);
	}

	private ParsedQuestion parseEssay(
		JsonNode questionNode,
		String questionId,
		String questionText,
		BigDecimal maxScore
	) {
		String modelAnswer = requiredText(questionNode, "modelAnswer");
		List<RubricCriterion> rubric = parseRubric(
			requiredArray(questionNode, "rubric")
		);
		BigDecimal weightSum = rubric.stream()
			.map(RubricCriterion::weight)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (weightSum.subtract(BigDecimal.ONE).abs()
			.compareTo(RUBRIC_WEIGHT_TOLERANCE) > 0) {
			throw invalidGeneration();
		}
		return new ParsedQuestion(
			new PublicQuizQuestion(questionId, questionText, maxScore, null),
			new PrivateQuizQuestion(
				questionId,
				null,
				null,
				null,
				null,
				null,
				rubric,
				modelAnswer
			)
		);
	}

	private List<String> parseTextArray(JsonNode arrayNode) {
		List<String> values = new ArrayList<>();
		for (JsonNode value : arrayNode) {
			if (!value.isString() || value.stringValue().isBlank()) {
				throw invalidGeneration();
			}
			values.add(value.stringValue().trim());
		}
		return List.copyOf(values);
	}

	private List<RubricCriterion> parseRubric(JsonNode rubricNode) {
		if (rubricNode.size() == 0) {
			throw invalidGeneration();
		}
		List<RubricCriterion> criteria = new ArrayList<>();
		for (JsonNode criterionNode : rubricNode) {
			BigDecimal weight = requiredDecimal(criterionNode, "weight");
			if (weight.compareTo(BigDecimal.ZERO) <= 0) {
				throw invalidGeneration();
			}
			criteria.add(new RubricCriterion(
				requiredText(criterionNode, "criterion"),
				weight
			));
		}
		return List.copyOf(criteria);
	}

	private QuizType requiredQuizType(JsonNode generation) {
		String value = requiredText(generation, "quizType");
		try {
			return QuizType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_QUIZ_TYPE);
		}
	}

	private String requiredText(JsonNode node, String field) {
		if (node == null || !node.isObject()) {
			throw invalidGeneration();
		}
		JsonNode value = node.get(field);
		if (value == null || !value.isString() || value.stringValue().isBlank()) {
			throw invalidGeneration();
		}
		return value.stringValue().trim();
	}

	private int requiredPositiveInt(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null
			|| !value.isIntegralNumber()
			|| !value.canConvertToInt()
			|| value.intValue() < 1) {
			throw invalidGeneration();
		}
		return value.intValue();
	}

	private BigDecimal requiredPositiveDecimal(JsonNode node, String field) {
		BigDecimal value = requiredDecimal(node, field);
		if (value.compareTo(BigDecimal.ZERO) <= 0
			|| value.precision() > 10
			|| Math.max(0, value.stripTrailingZeros().scale()) > 2) {
			throw invalidGeneration();
		}
		return value;
	}

	private BigDecimal requiredDecimal(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		if (value == null || !value.isNumber()) {
			throw invalidGeneration();
		}
		return value.decimalValue();
	}

	private JsonNode requiredArray(JsonNode node, String field) {
		if (node == null || !node.isObject()) {
			throw invalidGeneration();
		}
		JsonNode value = node.get(field);
		if (value == null || !value.isArray()) {
			throw invalidGeneration();
		}
		return value;
	}

	private BusinessException invalidGeneration() {
		return new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}

	private record ParsedQuestion(
		PublicQuizQuestion publicQuestion,
		PrivateQuizQuestion privateQuestion
	) {
	}
}
