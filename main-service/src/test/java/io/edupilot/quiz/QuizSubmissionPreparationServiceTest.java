package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.session.LearningSession;
import io.edupilot.user.User;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class QuizSubmissionPreparationServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private QuizRepository quizRepository;

	@Mock
	private QuizSubmissionRepository submissionRepository;

	@Mock
	private MaterialPageRepository materialPageRepository;

	private QuizSubmissionPreparationService service;
	private Quiz quiz;

	@BeforeEach
	void setUp() {
		service = new QuizSubmissionPreparationService(
			quizRepository,
			submissionRepository,
			materialPageRepository
		);
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(3);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		quiz = Quiz.create(
			session,
			1,
			"퀴즈",
			1,
			1,
			QuizType.MCQ,
			List.of(new PublicQuizQuestion(
				"q1",
				"문항",
				new BigDecimal("10.00"),
				List.of(
					new QuizOption("a", "A"),
					new QuizOption("b", "B")
				)
			)),
			List.of(new PrivateQuizQuestion(
				"q1",
				"a",
				null,
				"설명",
				null,
				null,
				null,
				null
			)),
			"1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 50L);
		when(quizRepository.findOwned(50L, 1L)).thenReturn(Optional.of(quiz));
	}

	@Test
	void acceptsKnownOptionAndPreservesQuestionOrder() throws Exception {
		QuizSubmitRequest request = new QuizSubmitRequest(
			"request-1",
			List.of(new QuizSubmitRequest.Answer(
				"q1",
				objectMapper.readTree("\"a\"")
			))
		);

		PreparedQuizSubmission prepared = service.prepare(1L, 50L, request);

		assertThat(prepared.answers()).containsExactly(
			new SubmittedAnswer("q1", "a")
		);
	}

	@Test
	void rejectsUnknownDuplicateAndNonTextAnswers() throws Exception {
		assertInvalid(new QuizSubmitRequest(
			"request-1",
			List.of(new QuizSubmitRequest.Answer(
				"unknown",
				objectMapper.readTree("\"a\"")
			))
		));
		assertInvalid(new QuizSubmitRequest(
			"request-1",
			List.of(
				new QuizSubmitRequest.Answer(
					"q1",
					objectMapper.readTree("\"a\"")
				),
				new QuizSubmitRequest.Answer(
					"q1",
					objectMapper.readTree("\"a\"")
				)
			)
		));
		assertInvalid(new QuizSubmitRequest(
			"request-1",
			List.of(new QuizSubmitRequest.Answer(
				"q1",
				objectMapper.readTree("true")
			))
		));
	}

	@Test
	void rejectsExistingSubmissionBeforeRequestValidation() {
		when(submissionRepository.existsByQuiz_IdAndUser_Id(50L, 1L))
			.thenReturn(true);

		assertThatThrownBy(() -> service.prepare(1L, 50L, null))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED)
			);
	}

	private void assertInvalid(QuizSubmitRequest request) {
		assertThatThrownBy(() -> service.prepare(1L, 50L, request))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.INVALID_QUIZ_ANSWER)
			);
	}
}
