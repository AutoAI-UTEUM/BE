package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

import io.edupilot.ai.dto.QuizGeneration;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import tools.jackson.databind.ObjectMapper;

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
			sessionRepository,
			new QuizGenerationValidator()
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
		ReflectionTestUtils.setField(session, "currentPage", 3);
		lenient().when(sessionRepository.findById(100L))
			.thenReturn(Optional.of(session));
	}

	@Test
	void createsAllFourTypesAndSeparatesPrivateFields() throws Exception {
		when(quizRepository.saveAndFlush(any(Quiz.class))).thenAnswer(invocation -> {
			Quiz quiz = invocation.getArgument(0);
			ReflectionTestUtils.setField(quiz, "id", 50L);
			return quiz;
		});

		for (QuizType quizType : QuizType.values()) {
			assertThat(quizService.createFromGeneration(
				100L,
				"1.0",
				generation(quizType, 5, 5)
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
		for (Quiz quiz : captor.getAllValues()) {
			assertThat(quiz.getPublicQuestions()).hasSize(5);
			assertThat(quiz.getPrivateQuestions()).hasSize(5);
			String publicJson = objectMapper.writeValueAsString(
				quiz.getPublicQuestions()
			);
			assertThat(publicJson)
				.contains("\"points\"")
				.doesNotContain("\"maxScore\"")
				.doesNotContain("answerChoiceId")
				.doesNotContain("correctOptionId")
				.doesNotContain("answerValue")
				.doesNotContain("correctAnswer")
				.doesNotContain("explanation")
				.doesNotContain("referenceAnswer")
				.doesNotContain("gradingCriteria")
				.doesNotContain("modelAnswer")
				.doesNotContain("rubric");
			if (quiz.getQuizType() == QuizType.MCQ) {
				assertThat(publicJson)
					.contains("\"choices\"")
					.doesNotContain("\"options\"");
			}
		}

		String privateMcqJson = objectMapper.writeValueAsString(
			captor.getAllValues().getFirst().getPrivateQuestions()
		);
		String privateOxJson = objectMapper.writeValueAsString(
			captor.getAllValues().get(1).getPrivateQuestions()
		);
		assertThat(privateMcqJson)
			.contains("\"answerChoiceId\"")
			.doesNotContain("\"correctOptionId\"");
		assertThat(privateOxJson)
			.contains("\"answerValue\"")
			.doesNotContain("\"correctAnswer\"");
		assertThat(captor.getAllValues().get(0)
			.getPrivateQuestions().getFirst().answerChoiceId()).isEqualTo("a");
		assertThat(captor.getAllValues().get(1)
			.getPrivateQuestions().getFirst().answerValue()).isTrue();
		assertThat(captor.getAllValues().get(2)
			.getPrivateQuestions().getFirst().gradingCriteria())
			.containsExactly("정확성", "핵심 개념");
		assertThat(captor.getAllValues().get(3)
			.getPrivateQuestions().getFirst().rubric()).hasSize(2);

		Quiz savedMcq = captor.getAllValues().getFirst();
		when(quizRepository.findOwned(50L, 1L))
			.thenReturn(Optional.of(savedMcq));
		when(submissionRepository.existsByQuiz_IdAndUser_Id(50L, 1L))
			.thenReturn(false);
		String detailJson = objectMapper.writeValueAsString(
			quizService.detail(1L, 50L)
		);
		assertThat(detailJson)
			.contains("\"quizId\":50")
			.contains("\"submitted\":false")
			.contains("\"questionId\":\"q1\"")
			.contains("\"maxScore\":20.00")
			.contains("\"options\"")
			.doesNotContain("\"points\"")
			.doesNotContain("\"choices\"")
			.doesNotContain("answerChoiceId")
			.doesNotContain("correctOptionId")
			.doesNotContain("answerValue")
			.doesNotContain("correctAnswer")
			.doesNotContain("explanation")
			.doesNotContain("referenceAnswer")
			.doesNotContain("gradingCriteria")
			.doesNotContain("modelAnswer")
			.doesNotContain("rubric");
	}

	@Test
	void rejectsCountCoverageDuplicateAndMcqAnswerViolations() {
		assertInvalid(generation(QuizType.MCQ, 5, 4));

		QuizGeneration outsideSnapshot = generation(QuizType.MCQ, 5, 5);
		outsideSnapshot = copy(
			outsideSnapshot,
			new QuizGeneration.Coverage(1, 4),
			outsideSnapshot.questions()
		);
		assertInvalid(outsideSnapshot);

		QuizGeneration duplicate = generation(QuizType.MCQ, 5, 5);
		List<QuizGeneration.Question> duplicateQuestions =
			new ArrayList<>(duplicate.questions());
		QuizGeneration.Question second = duplicateQuestions.get(1);
		duplicateQuestions.set(1, copyQuestion(second, "q1", second.choices()));
		assertInvalid(copy(duplicate, duplicate.coverage(), duplicateQuestions));

		QuizGeneration unknownAnswer = generation(QuizType.MCQ, 5, 5);
		List<QuizGeneration.Question> answerQuestions =
			new ArrayList<>(unknownAnswer.questions());
		QuizGeneration.Question first = answerQuestions.getFirst();
		answerQuestions.set(0, new QuizGeneration.Question(
			first.questionId(),
			first.questionText(),
			first.points(),
			first.choices(),
			"missing",
			first.explanation(),
			first.answerValue(),
			first.referenceAnswer(),
			first.gradingCriteria(),
			first.modelAnswer(),
			first.rubric()
		));
		assertInvalid(copy(
			unknownAnswer,
			unknownAnswer.coverage(),
			answerQuestions
		));
	}

	@Test
	void storesCheckpointCoverageWhileKeepingQuizOnCurrentPage() {
		when(quizRepository.saveAndFlush(any(Quiz.class))).thenAnswer(invocation -> {
			Quiz quiz = invocation.getArgument(0);
			ReflectionTestUtils.setField(quiz, "id", 50L);
			return quiz;
		});
		QuizGeneration generation = generation(QuizType.MCQ, 5, 5);
		generation = copy(
			generation,
			new QuizGeneration.Coverage(1, 3),
			generation.questions()
		);

		assertThat(quizService.createFromGeneration(100L, "1.0", generation))
			.isEqualTo(50L);

		ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
		org.mockito.Mockito.verify(quizRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getPageNumber()).isEqualTo(3);
		assertThat(captor.getValue().getCoverageStartPage()).isEqualTo(1);
		assertThat(captor.getValue().getCoverageEndPage()).isEqualTo(3);
	}

	@Test
	void rejectsEssayRubricWeightOutsideTolerance() {
		QuizGeneration generation = generation(QuizType.ESSAY, 5, 5);
		List<QuizGeneration.Question> questions =
			new ArrayList<>(generation.questions());
		QuizGeneration.Question first = questions.getFirst();
		questions.set(0, new QuizGeneration.Question(
			first.questionId(),
			first.questionText(),
			first.points(),
			first.choices(),
			first.answerChoiceId(),
			first.explanation(),
			first.answerValue(),
			first.referenceAnswer(),
			first.gradingCriteria(),
			first.modelAnswer(),
			List.of(
				new QuizGeneration.Rubric(
					"정확성",
					new BigDecimal("0.7")
				),
				new QuizGeneration.Rubric(
					"논리성",
					new BigDecimal("0.2")
				)
			)
		));

		assertInvalid(copy(generation, generation.coverage(), questions));
	}

	@Test
	void listUsesDefensiveLatestOneHundredLimit() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(quizRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
			eq(100L),
			any(Pageable.class)
		)).thenReturn(List.of());

		quizService.list(1L, 100L);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		org.mockito.Mockito.verify(quizRepository)
			.findBySession_IdOrderByCreatedAtDescIdDesc(
				eq(100L),
				pageable.capture()
			);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
	}

	private QuizGeneration generation(
		QuizType quizType,
		int questionCount,
		int arraySize
	) {
		List<QuizGeneration.Question> questions = IntStream
			.rangeClosed(1, arraySize)
			.mapToObj(index -> question(quizType, index))
			.toList();
		return new QuizGeneration(
			"1.0",
			"generation-1",
			quizType.name(),
			new QuizGeneration.Coverage(3, 3),
			quizType + " 퀴즈",
			questionCount,
			questions
		);
	}

	private QuizGeneration.Question question(QuizType quizType, int index) {
		String questionId = "q" + index;
		String questionText = "문항 " + index;
		BigDecimal points = new BigDecimal("20.00");
		return switch (quizType) {
			case MCQ -> new QuizGeneration.Question(
				questionId,
				questionText,
				points,
				List.of(
					new QuizGeneration.Choice("a", "정답"),
					new QuizGeneration.Choice("b", "오답")
				),
				"a",
				"핵심 설명",
				null,
				null,
				null,
				null,
				null
			);
			case OX -> new QuizGeneration.Question(
				questionId,
				questionText,
				points,
				null,
				null,
				"핵심 설명",
				true,
				null,
				null,
				null,
				null
			);
			case SHORT -> new QuizGeneration.Question(
				questionId,
				questionText,
				points,
				null,
				null,
				null,
				null,
				"기준 답안",
				List.of("정확성", "핵심 개념"),
				null,
				null
			);
			case ESSAY -> new QuizGeneration.Question(
				questionId,
				questionText,
				points,
				null,
				null,
				null,
				null,
				null,
				null,
				"모범 답안",
				List.of(
					new QuizGeneration.Rubric(
						"정확성",
						new BigDecimal("0.6")
					),
					new QuizGeneration.Rubric(
						"논리성",
						new BigDecimal("0.4")
					)
				)
			);
		};
	}

	private QuizGeneration copy(
		QuizGeneration source,
		QuizGeneration.Coverage coverage,
		List<QuizGeneration.Question> questions
	) {
		return new QuizGeneration(
			source.schemaVersion(),
			source.generationId(),
			source.quizType(),
			coverage,
			source.title(),
			source.questionCount(),
			List.copyOf(questions)
		);
	}

	private QuizGeneration.Question copyQuestion(
		QuizGeneration.Question source,
		String questionId,
		List<QuizGeneration.Choice> choices
	) {
		return new QuizGeneration.Question(
			questionId,
			source.questionText(),
			source.points(),
			choices,
			source.answerChoiceId(),
			source.explanation(),
			source.answerValue(),
			source.referenceAnswer(),
			source.gradingCriteria(),
			source.modelAnswer(),
			source.rubric()
		);
	}

	private void assertInvalid(QuizGeneration generation) {
		assertThatThrownBy(() ->
			quizService.createFromGeneration(100L, "1.0", generation))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
	}
}
