package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class QuizSubmissionPersistenceServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private QuizRepository quizRepository;

	@Mock
	private QuizSubmissionRepository submissionRepository;

	@Mock
	private UserRepository userRepository;

	@Test
	void persistsSubmissionAndClearsActiveQuiz() {
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(2);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		ReflectionTestUtils.setField(session, "activeQuizId", 50L);
		Quiz quiz = Quiz.create(
			session,
			1,
			"퀴즈",
			1,
			1,
			QuizType.MCQ,
			List.of(),
			List.of(),
			"1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 50L);
		GradingResult result = new GradingResult(
			"1.0",
			new BigDecimal("60.00"),
			new BigDecimal("100.00"),
			List.of()
		);
		PreparedQuizSubmission prepared = new PreparedQuizSubmission(
			50L,
			100L,
			10L,
			QuizType.MCQ,
			"1.0",
			"request-1",
			List.of(),
			List.of(),
			List.of(),
			null
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(quizRepository.findByIdAndSessionId(50L, 100L))
			.thenReturn(Optional.of(quiz));
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(submissionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			QuizSubmission submission = invocation.getArgument(0);
			ReflectionTestUtils.setField(submission, "id", 200L);
			return submission;
		});
		QuizSubmissionPersistenceService service =
			new QuizSubmissionPersistenceService(
				sessionRepository,
				quizRepository,
				submissionRepository,
				userRepository
			);

		var response = service.persist(1L, prepared, result, true);

		assertThat(response.submissionId()).isEqualTo(200L);
		assertThat(session.getActiveQuizId()).isNull();
		assertThat(session.getLastUiActions().getFirst().yesEvent())
			.isEqualTo("MOVE_NEXT_PAGE");
	}

	@Test
	void finalLockRecheckRejectsConcurrentDuplicateBeforeSaving() {
		Fixture fixture = fixture();
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));
		when(quizRepository.findByIdAndSessionId(50L, 100L))
			.thenReturn(Optional.of(fixture.quiz()));
		when(submissionRepository.existsByQuiz_IdAndUser_Id(50L, 1L))
			.thenReturn(true);
		QuizSubmissionPersistenceService service = service();

		assertThatThrownBy(() -> service.persist(
			1L,
			fixture.prepared(),
			fixture.result(),
			true
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.QUIZ_ALREADY_SUBMITTED)
		);
		verify(submissionRepository, never()).saveAndFlush(any());
	}

	@Test
	void finalLockRecheckRejectsCompletedSession() {
		Fixture fixture = fixture();
		fixture.session().complete();
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));
		QuizSubmissionPersistenceService service = service();

		assertThatThrownBy(() -> service.persist(
			1L,
			fixture.prepared(),
			fixture.result(),
			true
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.QUIZ_NOT_SUBMITTABLE)
		);
		verify(quizRepository, never()).findByIdAndSessionId(any(), any());
		verify(submissionRepository, never()).saveAndFlush(any());
	}

	private QuizSubmissionPersistenceService service() {
		return new QuizSubmissionPersistenceService(
			sessionRepository,
			quizRepository,
			submissionRepository,
			userRepository
		);
	}

	private Fixture fixture() {
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(2);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		Quiz quiz = Quiz.create(
			session,
			1,
			"퀴즈",
			1,
			1,
			QuizType.MCQ,
			List.of(),
			List.of(),
			"1.0"
		);
		ReflectionTestUtils.setField(quiz, "id", 50L);
		PreparedQuizSubmission prepared = new PreparedQuizSubmission(
			50L,
			100L,
			10L,
			QuizType.MCQ,
			"1.0",
			"request-1",
			List.of(),
			List.of(),
			List.of(),
			null
		);
		GradingResult result = new GradingResult(
			"1.0",
			new BigDecimal("60.00"),
			new BigDecimal("100.00"),
			List.of()
		);
		return new Fixture(session, quiz, prepared, result);
	}

	private record Fixture(
		LearningSession session,
		Quiz quiz,
		PreparedQuizSubmission prepared,
		GradingResult result
	) {
	}
}
