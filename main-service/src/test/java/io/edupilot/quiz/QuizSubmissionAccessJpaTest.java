package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.assessment.AssessmentPersistenceService;
import io.edupilot.diagnosis.DiagnosisPersistenceService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomStudentService;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.UiAction;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import tools.jackson.databind.node.StringNode;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:quiz-access;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/quiz-access"
})
@ActiveProfiles("jpa-context")
class QuizSubmissionAccessJpaTest {
	private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private QuizRepository quizzes;
	@Autowired private QuizService quizService;
	@Autowired private QuizSubmissionRepository submissions;
	@Autowired private QuizSubmissionService service;
	@Autowired private QuizSubmissionPreparationService preparation;
	@Autowired private QuizSubmissionPersistenceService persistence;
	@Autowired private QuizGradingService grading;
	@Autowired private AssessmentPersistenceService assessmentPersistence;
	@Autowired private DiagnosisPersistenceService diagnosisPersistence;
	@Autowired private ClassroomStudentService students;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoBean private AiUsageService usage;
	@MockitoBean private QuizPostGradingHook postGrading;
	@MockitoBean private FileStorage storage;
	@MockitoBean private Clock clock;

	@BeforeEach
	void configureStubs() {
		when(clock.instant()).thenReturn(NOW);
		when(postGrading.onGraded(any())).thenReturn(List.of(UiAction.moveNextPage()));
	}

	@ParameterizedTest
	@EnumSource(value = QuizType.class, names = {"OX"})
	void paginationIncludesRecordsBeyondOneHundredWithStableTieOrdering(QuizType type) {
		Fixture f = fixture(type, true);
		inTransaction(() -> {
			LearningSession session = sessions.findById(f.session()).orElseThrow();
			for (int index = 0; index < 100; index++) {
				quizzes.save(Quiz.create(session, 1, "Pagination " + index, 1, 1, type,
					List.of(new PublicQuizQuestion("q1", "Synthetic", BigDecimal.TEN, null)),
					List.of(new PrivateQuizQuestion("q1", null, true, "Private", null, null, null, null)), "1.0"));
			}
			quizzes.flush();
			jdbc.update("update quizzes set created_at = ? where session_id = ?", NOW, f.session());
		});
		var first = quizService.list(f.learner(), f.session());
		var second = quizService.list(f.learner(), f.session(), 1, 100);
		var beyond = quizService.list(f.learner(), f.session(), 2, 100);
		assertThat(first.quizzes()).hasSize(100);
		assertThat(first.totalElements()).isEqualTo(101);
		assertThat(first.totalPages()).isEqualTo(2);
		assertThat(first.hasNext()).isTrue();
		assertThat(second.quizzes()).hasSize(1);
		assertThat(second.quizzes().getFirst().quizId()).isEqualTo(f.quiz());
		assertThat(second.hasNext()).isFalse();
		assertThat(beyond.quizzes()).isEmpty();
		assertThat(beyond.totalElements()).isEqualTo(101);
		assertThat(first.quizzes().stream().map(value -> value.quizId()).toList())
			.isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThatThrownBy(() -> quizService.list(f.instructor(), f.session(), 1, 100))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND));
	}

	@ParameterizedTest
	@EnumSource(value = QuizType.class, names = {"OX", "SHORT"})
	void revokedLastMembershipRejectsNewSubmissionWithoutChanges(QuizType type) {
		Fixture f = fixture(type, false);
		revoke(f);
		assertDenied(() -> service.submit(f.learner(), f.quiz(), request()));
		assertUnsubmitted(f);
		verify(ai, never()).grade(any());
		verify(postGrading, never()).onGraded(any());
	}

	@Test
	void revokedMembershipRejectsReplayAndDetailButPreservesRecord() {
		Fixture f = fixture(QuizType.OX, false);
		var saved = service.submit(f.learner(), f.quiz(), request());
		assertThat(saved.gradingResult().items().getFirst().feedback()).isEqualTo("private explanation");
		revoke(f);
		clearInvocations(postGrading);
		assertDenied(() -> service.submit(f.learner(), f.quiz(), request()));
		assertDenied(() -> service.detail(f.learner(), f.quiz()));
		assertDenied(() -> service.submit(f.learner(), f.quiz(), new QuizSubmitRequest("different-request", request().answers())));
		assertThat(submissions.findById(saved.submissionId())).isPresent();
		verify(postGrading, never()).onGraded(any());
	}

	@Test
	void materialOwnerAndAlternativeClassroomGrantRemainAllowed() {
		Fixture owner = fixture(QuizType.OX, true);
		revoke(owner);
		assertThat(service.submit(owner.learner(), owner.quiz(), request()).passed()).isTrue();
		assertThat(service.detail(owner.learner(), owner.quiz()).quizId()).isEqualTo(owner.quiz());
		Fixture member = fixture(QuizType.OX, false);
		inTransaction(() -> {
			linkClassroom(users.getReferenceById(member.instructor()), users.getReferenceById(member.learner()),
				materials.getReferenceById(member.material()));
		});
		revoke(member);
		assertThat(service.submit(member.learner(), member.quiz(), request()).passed()).isTrue();
		assertThat(service.detail(member.learner(), member.quiz()).quizId()).isEqualTo(member.quiz());
	}

	@Test
	void anotherUsersQuizRemainsHidden() {
		Fixture f = fixture(QuizType.OX, false);
		assertThatThrownBy(() -> service.submit(f.instructor(), f.quiz(), request()))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.QUIZ_NOT_FOUND));
		assertUnsubmitted(f);
	}

	@Test
	void concurrentAuthorizedRequestsStoreOnceAndReplaySameResult() throws Exception {
		Fixture f = fixture(QuizType.SHORT, false);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		stubGrade(f, entered, finish);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> service.submit(f.learner(), f.quiz(), request()));
			try {
				await(entered);
				assertThatThrownBy(() -> service.submit(f.learner(), f.quiz(), request()))
					.isInstanceOfSatisfying(BusinessException.class,
						error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SESSION_STATE_CONFLICT));
			} finally {
				finish.countDown();
			}
			var saved = first.get(10, TimeUnit.SECONDS);
			assertThat(service.submit(f.learner(), f.quiz(), request()).submissionId()).isEqualTo(saved.submissionId());
			assertThat(submissionCount(f)).isEqualTo(1);
			assertClaimReleased(f);
		}
		verify(ai).grade(any());
	}

	@Test
	void revocationCommittedWhileAiWaitsRejectsPersistenceAndReleasesClaim() throws Exception {
		Fixture f = fixture(QuizType.SHORT, false);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		stubGrade(f, entered, finish);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var request = executor.submit(() -> service.submit(f.learner(), f.quiz(), request()));
			try {
				await(entered);
				revoke(f);
			} finally {
				finish.countDown();
			}
			assertThatThrownBy(() -> request.get(10, TimeUnit.SECONDS))
				.hasCauseInstanceOf(BusinessException.class)
				.satisfies(error -> assertThat(((BusinessException) error.getCause()).errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND));
		}
		assertUnsubmitted(f);
		verify(postGrading, never()).onGraded(any());
	}

	@Test
	void savingTransactionOrdersMembershipRemovalAfterCommit() throws Exception {
		Fixture f = fixture(QuizType.OX, false);
		var prepared = preparation.prepare(f.learner(), f.quiz(), request());
		var grade = grading.grade(f.learner(), prepared);
		CountDownLatch saved = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CountDownLatch removing = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var save = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
				var result = persistence.persist(f.learner(), prepared, grade, true);
				saved.countDown();
				await(commit);
				return result;
			}));
			await(saved);
			var removal = executor.submit(() -> { removing.countDown(); revoke(f); });
			try {
				await(removing);
				assertThatThrownBy(() -> removal.get(300, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			} finally {
				commit.countDown();
			}
			save.get(10, TimeUnit.SECONDS);
			removal.get(10, TimeUnit.SECONDS);
		}
		assertThat(submissionCount(f)).isEqualTo(1);
		assertDenied(() -> service.submit(f.learner(), f.quiz(), request()));
	}

	@Test
	void removalTransactionWinningRaceRejectsWaitingSave() throws Exception {
		Fixture f = fixture(QuizType.OX, false);
		var prepared = preparation.prepare(f.learner(), f.quiz(), request());
		var grade = grading.grade(f.learner(), prepared);
		CountDownLatch deleted = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		CountDownLatch saving = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var removal = executor.submit(() -> inTransaction(() -> {
				revoke(f);
				members.flush();
				deleted.countDown();
				await(commit);
			}));
			await(deleted);
			var save = executor.submit(() -> {
				saving.countDown();
				return persistence.persist(f.learner(), prepared, grade, true);
			});
			try {
				await(saving);
				assertThatThrownBy(() -> save.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			} finally {
				commit.countDown();
			}
			removal.get(10, TimeUnit.SECONDS);
			assertThatThrownBy(() -> save.get(10, TimeUnit.SECONDS))
				.hasCauseInstanceOf(BusinessException.class)
				.satisfies(error -> assertThat(((BusinessException) error.getCause()).errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND));
		}
		assertUnsubmitted(f);
	}

	@Test
	void revocationAfterSaveSuppressesResponseAndPreservesCommittedHistory() {
		Fixture f = fixture(QuizType.OX, false);
		when(postGrading.onGraded(any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(submissionCount(f)).isEqualTo(1);
			revoke(f);
			return List.of(UiAction.moveNextPage());
		});

		assertDenied(() -> service.submit(f.learner(), f.quiz(), request()));
		assertThat(submissionCount(f)).isEqualTo(1);
		assertClaimReleased(f);
		assertDenied(() -> service.submit(f.learner(), f.quiz(), request()));
	}

	@Test
	void revokedAccessRejectsLateAssessmentMemoryAndDiagnosisWrites() {
		Fixture f = fixture(QuizType.OX, false);
		var prepared = preparation.prepare(f.learner(), f.quiz(), request());
		var grade = grading.grade(f.learner(), prepared);
		var saved = service.submit(f.learner(), f.quiz(), request());
		var context = new QuizPostGradingContext(saved.submissionId(), f.quiz(), f.session(), f.learner(),
			f.material(), QuizType.OX, "1.0", prepared.publicQuestions(), prepared.privateQuestions(),
			prepared.answers(), grade, false, prepared.pageContext(), saved.uiActions());
		revoke(f);

		assertDenied(() -> assessmentPersistence.save(context, new QuizAssessmentResponse("1.0", "synthetic summary",
			List.of(), List.of(), List.of(), "REVIEW",
			List.of(new QuizAssessmentResponse.MemoryCandidate("MISCONCEPTION", "synthetic candidate", new BigDecimal("0.8"))),
			List.of(), null)));
		assertDenied(() -> diagnosisPersistence.savePending(context,
			new DiagnosisResponse("1.0", List.of(), List.of(), "synthetic prompt", List.of(), "hint", null)));
		assertThat(jdbc.queryForObject("select count(*) from quiz_assessments where quiz_submission_id = ?", Long.class, saved.submissionId())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from diagnoses where quiz_submission_id = ?", Long.class, saved.submissionId())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from learner_memory_candidates where user_id = ?", Long.class, f.learner())).isZero();
		assertThat(jdbc.queryForObject("select pending_diagnosis_id from learning_sessions where id = ?", Long.class, f.session())).isNull();
		assertClaimReleased(f);
	}

	@Test
	void lastMaterialLinkRemovalAlsoRejectsPreparedSubmission() {
		Fixture f = fixture(QuizType.OX, false);
		var prepared = preparation.prepare(f.learner(), f.quiz(), request());
		var grade = grading.grade(f.learner(), prepared);
		inTransaction(() -> jdbc.update("delete from classroom_week_materials where material_id = ?", f.material()));
		assertDenied(() -> persistence.persist(f.learner(), prepared, grade, true));
		assertUnsubmitted(f);
	}

	@Test
	void gradingFailureReleasesActualClaimWithoutSaving() {
		Fixture f = fixture(QuizType.SHORT, false);
		when(ai.grade(any())).thenThrow(new BusinessException(ErrorCode.GRADING_RESULT_INVALID));
		assertThatThrownBy(() -> service.submit(f.learner(), f.quiz(), request()))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.GRADING_RESULT_INVALID));
		assertUnsubmitted(f);
		verify(postGrading, never()).onGraded(any());
	}

	private void stubGrade(Fixture f, CountDownLatch entered, CountDownLatch finish) {
		when(ai.grade(any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			entered.countDown();
			await(finish);
			return new GradeResponse("1.0", f.quiz(), "SHORT", BigDecimal.TEN, BigDecimal.TEN,
				List.of(new GradeResponse.Item("q1", BigDecimal.TEN, BigDecimal.TEN, "CORRECT", "private feedback")), null);
		});
	}

	private Fixture fixture(QuizType type, boolean materialOwner) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			String suffix = UUID.randomUUID().toString();
			User instructor = users.saveAndFlush(User.create(suffix + "-teacher@example.com", "hash", "Teacher", UserRole.INSTRUCTOR));
			User learner = users.saveAndFlush(User.create(suffix + "-student@example.com", "hash", "Student", UserRole.LEARNER));
			LearningMaterial material = LearningMaterial.create(materialOwner ? learner : instructor, "Fixture", "materials/" + suffix + ".pdf");
			material.markReady(2);
			materials.saveAndFlush(material);
			Classroom classroom = linkClassroom(instructor, learner, material);
			LearningSession session = sessions.saveAndFlush(LearningSession.create(learner, material));
			Quiz quiz = quizzes.saveAndFlush(Quiz.create(session, 1, "Fixture quiz", 1, 1, type,
				List.of(new PublicQuizQuestion("q1", "Synthetic question", BigDecimal.TEN, null)),
				List.of(new PrivateQuizQuestion("q1", null, true, "private explanation", "true", List.of("criterion"), null, null)), "1.0"));
			session.activateQuiz(quiz.getId(), List.of());
			sessions.flush();
			return new Fixture(instructor.getId(), learner.getId(), classroom.getId(), material.getId(), session.getId(), quiz.getId());
		});
	}

	private Classroom linkClassroom(User instructor, User learner, LearningMaterial material) {
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(instructor, "Fixture", LocalDate.of(2026, 10, 1),
			LocalDate.of(2026, 12, 1), ClassroomColor.BLUE, null, UUID.randomUUID().toString().substring(0, 9)));
		members.saveAndFlush(ClassroomMember.create(classroom, learner, NOW));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(classroom, 1, "Week", null, ClassroomWeekStatus.PRIVATE, 1));
		links.saveAndFlush(ClassroomWeekMaterial.create(week, material, NOW));
		return classroom;
	}

	private void revoke(Fixture f) {
		students.remove(f.instructor(), UserRole.INSTRUCTOR, f.classroom(), f.learner());
		assertThat(members.findByClassroom_IdAndUser_Id(f.classroom(), f.learner())).isEmpty();
	}

	private void assertUnsubmitted(Fixture f) {
		assertThat(submissionCount(f)).isZero();
		assertThat(jdbc.queryForObject("select active_quiz_id from learning_sessions where id = ?", Long.class, f.session())).isEqualTo(f.quiz());
		assertThat(jdbc.queryForObject("select page_status from learning_sessions where id = ?", String.class, f.session())).isEqualTo("QUIZ_READY");
		assertThat(jdbc.queryForObject("select count(*) from learner_memory_candidates where user_id = ?", Long.class, f.learner())).isZero();
		assertClaimReleased(f);
	}

	private long submissionCount(Fixture f) {
		return jdbc.queryForObject("select count(*) from quiz_submissions where quiz_id = ?", Long.class, f.quiz());
	}

	private void assertClaimReleased(Fixture f) {
		assertThat(jdbc.queryForObject("select active_turn_request_id from learning_sessions where id = ?", String.class, f.session())).isNull();
	}

	private QuizSubmitRequest request() {
		return new QuizSubmitRequest("request-1", List.of(new QuizSubmitRequest.Answer("q1", StringNode.valueOf("true"))));
	}

	private void assertDenied(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.MATERIAL_NOT_FOUND));
	}

	private void inTransaction(Runnable action) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> action.run());
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new AssertionError(error);
		}
	}

	private record Fixture(Long instructor, Long learner, Long classroom, Long material, Long session, Long quiz) { }
}
