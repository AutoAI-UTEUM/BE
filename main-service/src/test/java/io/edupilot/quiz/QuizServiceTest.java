package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private QuizRepository quizRepository;

	@Mock
	private QuizSubmissionRepository submissionRepository;

	@Mock
	private LearningSessionRepository sessionRepository;

	private QuizService quizService;
	private LearningSession session;

	@BeforeEach
	void setUp() {
		quizService = new QuizService(
			quizRepository,
			submissionRepository,
			sessionRepository
		);
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(10);
		session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		lenient().when(sessionRepository.findById(100L))
			.thenReturn(Optional.of(session));
	}

	@Test
	void createFromGenerationSeparatesPublicAndPrivateFields() throws Exception {
		when(quizRepository.saveAndFlush(any(Quiz.class))).thenAnswer(invocation -> {
			Quiz quiz = invocation.getArgument(0);
			ReflectionTestUtils.setField(quiz, "id", 50L);
			return quiz;
		});
		Long quizId = quizService.createFromGeneration(100L, mcqGeneration(5, 5));

		assertThat(quizId).isEqualTo(50L);
		ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
		org.mockito.Mockito.verify(quizRepository).saveAndFlush(captor.capture());
		Quiz saved = captor.getValue();
		assertThat(saved.getPublicQuestions()).hasSize(5);
		assertThat(saved.getPublicQuestions().getFirst().options()).hasSize(2);
		assertThat(saved.getPrivateQuestions().getFirst().correctOptionId())
			.isEqualTo("a");

		String publicJson = objectMapper.writeValueAsString(
			saved.getPublicQuestions()
		);
		assertThat(publicJson)
			.doesNotContain("correctOptionId")
			.doesNotContain("correctAnswer")
			.doesNotContain("referenceAnswer")
			.doesNotContain("modelAnswer")
			.doesNotContain("rubric")
			.doesNotContain("unknownSecret");
	}

	@Test
	void createsAllFourSupportedQuizTypes() throws Exception {
		when(quizRepository.saveAndFlush(any(Quiz.class))).thenAnswer(invocation -> {
			Quiz quiz = invocation.getArgument(0);
			ReflectionTestUtils.setField(quiz, "id", 50L);
			return quiz;
		});

		for (QuizType quizType : QuizType.values()) {
			assertThat(quizService.createFromGeneration(
				100L,
				generation(quizType)
			)).isEqualTo(50L);
		}

		ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
		org.mockito.Mockito.verify(quizRepository, times(4))
			.saveAndFlush(captor.capture());
		assertThat(captor.getAllValues())
			.extracting(Quiz::getQuizType)
			.containsExactly(
				QuizType.MCQ,
				QuizType.OX,
				QuizType.SHORT,
				QuizType.ESSAY
			);
		assertThat(captor.getAllValues())
			.allSatisfy(quiz -> {
				assertThat(quiz.getPublicQuestions()).hasSize(5);
				assertThat(quiz.getPrivateQuestions()).hasSize(5);
			});
	}

	@Test
	void rejectsQuestionCountMismatchAndUnsupportedType() throws Exception {
		assertError(
			mcqGeneration(5, 4),
			ErrorCode.AI_RESPONSE_INVALID
		);
		JsonNode unsupported = objectMapper.readTree("""
			{
			  "schemaVersion": "1.0",
			  "generationId": "generation-1",
			  "quizType": "UNKNOWN",
			  "page": 3,
			  "coverageStartPage": 1,
			  "coverageEndPage": 3,
			  "title": "퀴즈",
			  "questionCount": 5,
			  "questions": []
			}
			""");
		assertError(unsupported, ErrorCode.UNSUPPORTED_QUIZ_TYPE);
	}

	@Test
	void rejectsInvalidRangeDuplicateIdScoreAndMcqOption() throws Exception {
		ObjectNode invalidRange = (ObjectNode)mcqGeneration(5, 5);
		invalidRange.put("coverageEndPage", 11);
		assertError(invalidRange, ErrorCode.AI_RESPONSE_INVALID);

		ObjectNode duplicateId = (ObjectNode)mcqGeneration(5, 5);
		((ObjectNode)duplicateId.get("questions").get(1))
			.put("questionId", "q1");
		assertError(duplicateId, ErrorCode.AI_RESPONSE_INVALID);

		ObjectNode invalidScore = (ObjectNode)mcqGeneration(5, 5);
		((ObjectNode)invalidScore.get("questions").get(0))
			.put("maxScore", 0);
		assertError(invalidScore, ErrorCode.AI_RESPONSE_INVALID);

		ObjectNode unknownCorrectOption = (ObjectNode)mcqGeneration(5, 5);
		((ObjectNode)unknownCorrectOption.get("questions").get(0))
			.put("correctOptionId", "missing");
		assertError(unknownCorrectOption, ErrorCode.AI_RESPONSE_INVALID);
	}

	@Test
	void rejectsEssayRubricWeightOutsideTolerance() throws Exception {
		String questions = IntStream.rangeClosed(1, 5)
			.mapToObj(index -> """
				{
				  "questionId": "q%d",
				  "questionText": "서술형 %d",
				  "maxScore": 20,
				  "modelAnswer": "모범 답안",
				  "rubric": [
				    {"criterion": "정확성", "weight": 0.7},
				    {"criterion": "논리성", "weight": 0.2}
				  ]
				}
				""".formatted(index, index))
			.collect(java.util.stream.Collectors.joining(","));
		JsonNode generation = objectMapper.readTree("""
			{
			  "schemaVersion": "1.0",
			  "generationId": "generation-1",
			  "quizType": "ESSAY",
			  "page": 3,
			  "coverageStartPage": 1,
			  "coverageEndPage": 3,
			  "title": "서술형",
			  "questionCount": 5,
			  "questions": [%s]
			}
			""".formatted(questions));

		assertError(generation, ErrorCode.AI_RESPONSE_INVALID);

		ObjectNode negativeWeight = (ObjectNode)generation(QuizType.ESSAY);
		((ObjectNode)negativeWeight.get("questions").get(0)
			.get("rubric").get(0)).put("weight", -0.2);
		assertError(negativeWeight, ErrorCode.AI_RESPONSE_INVALID);
	}

	@Test
	void listUsesDefensiveLatestOneHundredLimit() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(quizRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
			eq(100L),
			any(Pageable.class)
		)).thenReturn(java.util.List.of());

		quizService.list(1L, 100L);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		org.mockito.Mockito.verify(quizRepository)
			.findBySession_IdOrderByCreatedAtDescIdDesc(
				eq(100L),
				pageable.capture()
			);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
	}

	private JsonNode mcqGeneration(int questionCount, int arraySize)
		throws Exception {
		String questions = IntStream.rangeClosed(1, arraySize)
			.mapToObj(index -> """
				{
				  "questionId": "q%d",
				  "questionText": "문항 %d",
				  "maxScore": 20,
				  "options": [
				    {"optionId": "a", "text": "정답"},
				    {"optionId": "b", "text": "오답"}
				  ],
				  "correctOptionId": "a",
				  "explanation": "핵심 설명",
				  "unknownSecret": "버려야 함"
				}
				""".formatted(index, index))
			.collect(java.util.stream.Collectors.joining(","));
		return objectMapper.readTree("""
			{
			  "schemaVersion": "1.0",
			  "generationId": "generation-1",
			  "quizType": "MCQ",
			  "page": 3,
			  "coverageStartPage": 1,
			  "coverageEndPage": 3,
			  "title": "MCQ",
			  "questionCount": %d,
			  "questions": [%s]
			}
			""".formatted(questionCount, questions));
	}

	private JsonNode generation(QuizType quizType) throws Exception {
		String typeFields = switch (quizType) {
			case MCQ -> """
				  "options": [
				    {"optionId": "a", "text": "정답"},
				    {"optionId": "b", "text": "오답"}
				  ],
				  "correctOptionId": "a",
				  "explanation": "핵심 설명"
				""";
			case OX -> """
				  "correctAnswer": true,
				  "explanation": "핵심 설명"
				""";
			case SHORT -> """
				  "referenceAnswer": "기준 답안",
				  "acceptableKeywords": ["핵심"],
				  "rubric": [{"criterion": "정확성", "weight": 1.0}]
				""";
			case ESSAY -> """
				  "modelAnswer": "모범 답안",
				  "rubric": [{"criterion": "논리성", "weight": 1.0}]
				""";
		};
		String questions = IntStream.rangeClosed(1, 5)
			.mapToObj(index -> """
				{
				  "questionId": "q%d",
				  "questionText": "문항 %d",
				  "maxScore": 20,
				%s
				}
				""".formatted(index, index, typeFields))
			.collect(java.util.stream.Collectors.joining(","));
		return objectMapper.readTree("""
			{
			  "schemaVersion": "1.0",
			  "generationId": "generation-1",
			  "quizType": "%s",
			  "page": 3,
			  "coverageStartPage": 1,
			  "coverageEndPage": 3,
			  "title": "%s",
			  "questionCount": 5,
			  "questions": [%s]
			}
			""".formatted(quizType, quizType, questions));
	}

	private void assertError(JsonNode generation, ErrorCode expected) {
		assertThatThrownBy(() ->
			quizService.createFromGeneration(100L, generation))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(expected)
			);
	}
}
