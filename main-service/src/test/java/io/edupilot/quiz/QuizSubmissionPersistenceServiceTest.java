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
import io.edupilot.diagnosis.DiagnosisRepository;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.PageStatus;
import io.edupilot.session.UiAction;
import io.edupilot.session.dto.SessionDetailResponse;
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

	@Mock
	private DiagnosisRepository diagnosisRepository;

	@Mock
	private MaterialAccessService materialAccessService;

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
				userRepository,
				new io.edupilot.session.UiActionResolver(),
				diagnosisRepository,
				materialAccessService
			);

		var persisted = service.persist(1L, prepared, result, true);
		var response = persisted.response();

		assertThat(response.submissionId()).isEqualTo(200L);
		assertThat(persisted.currentPageQuiz()).isTrue();
		assertThat(session.getPageStatus()).isEqualTo(PageStatus.EXPLAINED);
		assertThat(session.getActiveQuizId()).isNull();
		assertThat(session.getLastUiActions())
			.containsExactly(UiAction.moveNextPage());
		SessionDetailResponse restored = SessionDetailResponse.from(session);
		assertThat(restored.pageStatus()).isEqualTo(PageStatus.EXPLAINED);
		assertThat(restored.activeQuizId()).isNull();
		assertThat(restored.uiActions())
			.containsExactly(UiAction.moveNextPage());
	}

	@Test
	void passedQuizOnLastPageOffersSessionCompletion() {
		Fixture fixture = fixture();
		ReflectionTestUtils.setField(fixture.session(), "currentPage", 2);
		ReflectionTestUtils.setField(fixture.session(), "activeQuizId", 50L);
		ReflectionTestUtils.setField(fixture.quiz(), "pageNumber", 2);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));
		when(quizRepository.findByIdAndSessionId(50L, 100L))
			.thenReturn(Optional.of(fixture.quiz()));
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(submissionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			QuizSubmission submission = invocation.getArgument(0);
			ReflectionTestUtils.setField(submission, "id", 200L);
			return submission;
		});

		var persisted = service().persist(
			1L,
			fixture.prepared(),
			fixture.result(),
			true
		);

		assertThat(persisted.response().uiActions())
			.containsExactly(UiAction.completeSession());
		assertThat(fixture.session().getPageStatus())
			.isEqualTo(PageStatus.EXPLAINED);
		assertThat(fixture.session().getLastUiActions())
			.containsExactly(UiAction.completeSession());
	}

	@Test
	void failedQuizPreservesQuizReadyUntilDiagnosisStarts() {
		Fixture fixture = fixture();
		fixture.session().activateQuiz(50L, List.of(UiAction.quizProposal()));
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));
		when(quizRepository.findByIdAndSessionId(50L, 100L))
			.thenReturn(Optional.of(fixture.quiz()));
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(submissionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
			QuizSubmission submission = invocation.getArgument(0);
			ReflectionTestUtils.setField(submission, "id", 200L);
			return submission;
		});

		service().persist(
			1L,
			fixture.prepared(),
			fixture.result(),
			false
		);

		assertThat(fixture.session().getPageStatus())
			.isEqualTo(PageStatus.QUIZ_READY);
		assertThat(fixture.session().getActiveQuizId()).isNull();
		assertThat(fixture.session().getLastUiActions())
			.containsExactly(UiAction.moveNextPage());
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
	void finalLockRecheckRejectsQuizThatIsNoLongerActive() {
		Fixture fixture = fixture();
		ReflectionTestUtils.setField(fixture.session(), "activeQuizId", 51L);
		List<UiAction> beforeActions = fixture.session().getLastUiActions();
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(fixture.session()));
		when(quizRepository.findByIdAndSessionId(50L, 100L))
			.thenReturn(Optional.of(fixture.quiz()));

		assertThatThrownBy(() -> service().persist(
			1L,
			fixture.prepared(),
			fixture.result(),
			true
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.SESSION_STATE_CONFLICT)
		);
		assertThat(fixture.session().getPageStatus())
			.isEqualTo(PageStatus.NOT_EXPLAINED);
		assertThat(fixture.session().getLastUiActions())
			.isEqualTo(beforeActions);
		assertThat(fixture.session().getActiveQuizId()).isEqualTo(51L);
		verify(submissionRepository, never()).saveAndFlush(any());
	}

	@Test
	void submittingActiveQuizAfterMovingPagePreservesCurrentPageState() {
		User owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			owner,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(4);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		session.moveTo(3, PageStatus.EXPLAINED, List.of());
		session.activateQuiz(50L, List.of());
		session.moveTo(
			4,
			PageStatus.NOT_EXPLAINED,
			List.of(UiAction.pageExplanation())
		);
		Quiz quiz = Quiz.create(
			session,
			3,
			"퀴즈",
			3,
			3,
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

		PersistedQuizSubmission persisted = service().persist(
			1L,
			prepared,
			result,
			true
		);

		assertThat(persisted.currentPageQuiz()).isFalse();
		assertThat(session.getCurrentPage()).isEqualTo(4);
		assertThat(session.getPageStatus())
			.isEqualTo(PageStatus.NOT_EXPLAINED);
		assertThat(session.getLastUiActions())
			.containsExactly(UiAction.pageExplanation());
		assertThat(session.getActiveQuizId()).isNull();
		assertThat(persisted.response().uiActions())
			.containsExactly(UiAction.pageExplanation());
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

	@Test
	void replaysStoredSubmissionWithPersistedSessionActions() {
		Fixture fixture = fixture();
		User owner = (User) ReflectionTestUtils.getField(
			fixture.session(),
			"user"
		);
		QuizSubmission submission = QuizSubmission.create(
			fixture.quiz(),
			owner,
			"request-1",
			List.of(),
			fixture.result(),
			true
		);
		ReflectionTestUtils.setField(submission, "id", 200L);
		when(submissionRepository.findByRequest(50L, 1L, "request-1"))
			.thenReturn(Optional.of(submission));
		when(diagnosisRepository.findBySubmission_Id(200L))
			.thenReturn(Optional.empty());

		var replay = service().findByRequest(1L, 50L, "request-1");

		assertThat(replay).isPresent();
		assertThat(replay.orElseThrow().submissionId()).isEqualTo(200L);
		assertThat(replay.orElseThrow().gradingResult())
			.isEqualTo(io.edupilot.quiz.dto.QuizGradingResultResponse.from(
				fixture.result()
			));
		assertThat(replay.orElseThrow().uiActions())
			.containsExactly(UiAction.initialExplanation());
	}

	@Test
	void findsOwnedSubmissionDetailAndChecksMaterialAccess() {
		Fixture fixture = fixture();
		User owner = (User) ReflectionTestUtils.getField(
			fixture.session(),
			"user"
		);
		QuizSubmission submission = QuizSubmission.create(
			fixture.quiz(),
			owner,
			"request-1",
			List.of(),
			fixture.result(),
			true
		);
		ReflectionTestUtils.setField(submission, "id", 200L);
		when(submissionRepository.findOwnedByQuizId(50L, 1L))
			.thenReturn(Optional.of(submission));

		var detail = service().findDetail(1L, 50L);

		assertThat(detail).isPresent();
		assertThat(detail.orElseThrow().submissionId()).isEqualTo(200L);
		verify(materialAccessService).assertSessionAccessible(1L, 100L);
	}

	private QuizSubmissionPersistenceService service() {
		return new QuizSubmissionPersistenceService(
			sessionRepository,
			quizRepository,
			submissionRepository,
			userRepository,
			new io.edupilot.session.UiActionResolver(),
			diagnosisRepository,
			materialAccessService
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
