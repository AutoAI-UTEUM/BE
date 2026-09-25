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
import io.edupilot.session.LearningSession;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class QuizQuestionResultServiceTest {

	@Mock private QuizSubmissionRepository submissions;
	private QuizQuestionResultService service;

	@BeforeEach
	void setUp() {
		service = new QuizQuestionResultService(submissions);
	}

	@Test
	void reconstructsQuestionAndAnswersOnlyFromOwnedSubmission() {
		when(submissions.findByIdAndUser_Id(200L, 1L))
			.thenReturn(Optional.of(submission()));
		QuizQuestionResult result = service.requireOwned(1L, 200L, "q1");
		assertThat(result.questionText()).isEqualTo("문항");
		assertThat(result.choices()).containsExactly(
			new QuizOption("a", "오답"), new QuizOption("b", "정답"));
		assertThat(result.submittedAnswer()).isEqualTo("a");
		assertThat(result.correctAnswer()).isEqualTo("b");
		assertError(() -> service.requireOwned(1L, 200L, "missing"));
		assertError(() -> service.requireOwned(2L, 200L, "q1"));
	}

	private QuizSubmission submission() {
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(owner, "자료", "test.pdf");
		ReflectionTestUtils.setField(material, "id", 10L);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		Quiz quiz = Quiz.create(
			session, 1, "퀴즈", 1, 1, QuizType.MCQ,
			List.of(new PublicQuizQuestion("q1", "문항", BigDecimal.TEN,
				List.of(new QuizOption("a", "오답"), new QuizOption("b", "정답")))),
			List.of(new PrivateQuizQuestion("q1", "b", null, "해설",
				null, null, null, null)), "1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 50L);
		QuizSubmission submission = QuizSubmission.create(
			quiz, owner, "request-1", List.of(new SubmittedAnswer("q1", "a")),
			new GradingResult("1.0", BigDecimal.ZERO, BigDecimal.TEN,
				List.of(new GradingItem("q1", BigDecimal.ZERO, BigDecimal.TEN,
					GradingVerdict.WRONG, "오답"))), false
		);
		ReflectionTestUtils.setField(submission, "id", 200L);
		return submission;
	}

	private void assertError(Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
				.isEqualTo(ErrorCode.QUIZ_NOT_FOUND));
	}
}
