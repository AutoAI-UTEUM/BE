package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-grading-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-grading-recovery"
	}
)
@ActiveProfiles("jpa-context")
class ExamGradingRecoveryJpaTest {
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) { return; }
		registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:33316/runtime_exam_recovery_synthetic");
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private ExamAnswerRepository answerRepository;
	@Autowired private ExamSubmissionPersistenceService persistenceService;
	@Autowired private StudentExamService studentExamService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@Autowired private ExamAiGradingService aiGradingService;
	@Autowired private ExamGradingProperties gradingProperties;
	@MockitoSpyBean private ExamGradingDispatcher dispatcher;

	@MockitoBean private AiClient aiClient;
	@MockitoBean(name = "examGradingExecutor")
	private ThreadPoolTaskExecutor gradingExecutor;
	@MockitoBean private ExamGradingRecoveryScheduler recoveryScheduler;

	private User learner;
	private Exam exam;

	@BeforeEach
	void setUp() {
		if ("true".equals(System.getenv("RUNTIME_REGRESSIONS_MYSQL"))) {
			assertThat(jdbcTemplate.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbcTemplate.queryForObject("select database()", String.class)).isEqualTo("runtime_exam_recovery_synthetic");
		}
		jdbcTemplate.update("delete from notifications");
		answerRepository.deleteAll();
		submissionRepository.deleteAll();
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		User instructor = userRepository.save(io.edupilot.VerifiedTestUsers.verified(User.create(
			"recovery-instructor-" + suffix + "@example.com",
			"hash",
			"Instructor",
			UserRole.INSTRUCTOR
		)));
		learner = userRepository.save(io.edupilot.VerifiedTestUsers.verified(User.create(
			"recovery-learner-" + suffix + "@example.com",
			"hash",
			"Learner",
			UserRole.LEARNER
		)));
		Classroom classroom = classroomRepository.save(Classroom.create(
			instructor,
			"Recovery classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"REC" + suffix
		));
		memberRepository.save(ClassroomMember.create(
			classroom, learner, Instant.parse("2026-08-03T00:00:00Z")
		));
		exam = Exam.create(classroom, 1, "Recovery exam", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.save(exam);
		questionRepository.save(shortQuestion(exam));
	}

	@Test
	void executorRejectionLeavesCommittedSubmissionSubmittedForRecovery() {
		doThrow(new TaskRejectedException("queue full"))
			.when(gradingExecutor).execute(any(Runnable.class));

		var response = studentExamService.submit(
			learner.getId(),
			UserRole.LEARNER,
			exam.getId(),
			new SubmitExamRequest(
				"rejected-" + UUID.randomUUID(),
				List.of(new ExamAnswerRequest("q1", "answer"))
			)
		);

		ExamSubmission stored = submissionRepository.findById(response.submissionId())
			.orElseThrow();
		assertThat(response.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(stored.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(stored.getGradingLeaseToken()).isNull();
		assertThat(stored.getGradingLeaseUntil()).isEqualTo(Instant.EPOCH);
		verify(gradingExecutor).execute(any(Runnable.class));
	}

	@Test
	void cutoffUsesUpdatedAtWithTwentyNineAndThirtyOneMinuteBoundary() {
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		ExamSubmission recentAttempt = submission(1, now.minusSeconds(120 * 60L));
		ExamSubmission expiredAttempt = submission(2, now.minusSeconds(120 * 60L));
		touch(recentAttempt.getId(), now.minusSeconds(29 * 60L));
		touch(expiredAttempt.getId(), now.minusSeconds(31 * 60L));

		List<ExamGradingCandidate> requeued = persistenceService.requeueExpiredSubmissions(
			now.minusSeconds(30 * 60L), now, 100
		);
		entityManager.clear();

		assertThat(requeued).extracting(ExamGradingCandidate::submissionId)
			.containsExactly(expiredAttempt.getId());
		assertThat(submissionRepository.findById(recentAttempt.getId()).orElseThrow()
			.getGradingRetryCount()).isZero();
		ExamSubmission retried = submissionRepository.findById(expiredAttempt.getId())
			.orElseThrow();
		assertThat(retried.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(retried.getGradingRetryCount()).isEqualTo(1);
		assertThat(retried.getUpdatedAt()).isEqualTo(now);
	}

	@Test
	void cutoffRequeueCanClaimAndCompleteWithoutDuplicateAiApplication() {
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		ExamSubmission submission = submission(1, now.minusSeconds(120 * 60L));
		answerRepository.saveAndFlush(ExamAnswer.create(
			submission,
			questionRepository.findByExam_IdOrderByQuestionNo(exam.getId()).get(0),
			"answer",
			new BigDecimal("10.00")
		));
		touch(submission.getId(), now.minusSeconds(31 * 60L));

		assertThat(persistenceService.requeueExpiredSubmissions(
			now.minusSeconds(30 * 60L), now, 100
		)).hasSize(1);
		assertThat(persistenceService.claimGradingLease(
			submission.getId(), "lease-1", now.plusSeconds(1), now.plusSeconds(301)
		)).isTrue();
		ExamAiGradingOutcome outcome = new ExamAiGradingOutcome(
			Map.of("q1", new ExamAiGradingOutcome.GradedItem(
				new BigDecimal("8.00"), Verdict.PARTIAL, "feedback"
			)),
			false
		);

		assertThat(persistenceService.applyAiGrading(
			submission.getId(), "lease-1", outcome
		)).isTrue();
		assertThat(persistenceService.applyAiGrading(
			submission.getId(), "lease-1", outcome
		)).isFalse();
		ExamSubmission graded = submissionRepository.findById(submission.getId()).orElseThrow();
		assertThat(graded.getStatus()).isEqualTo(SubmissionStatus.GRADED);
		assertThat(graded.getGradingRetryCount()).isEqualTo(1);
		String dedupKey = "EXAM_GRADED:" + submission.getId() + ":" + learner.getId();
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from notifications where dedup_key = ?",
			Integer.class,
			dedupKey
		)).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
			"select type from notifications where dedup_key = ?",
			String.class,
			dedupKey
		)).isEqualTo("EXAM_GRADED");
	}

	@Test
	void threeRetriesExhaustedBecomesGradingFailed() {
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		ExamSubmission submission = submission(1, now.minusSeconds(120 * 60L));
		for (int retry = 1; retry <= 2; retry++) {
			touch(submission.getId(), now.minusSeconds(31 * 60L));
			assertThat(persistenceService.requeueExpiredSubmissions(
				now.minusSeconds(30 * 60L), now, 100
			)).hasSize(1);
			entityManager.clear();
			assertThat(submissionRepository.findById(submission.getId()).orElseThrow()
				.getGradingRetryCount()).isEqualTo(retry);
		}
		touch(submission.getId(), now.minusSeconds(31 * 60L));

		assertThat(persistenceService.failExhaustedSubmissions(
			now.minusSeconds(30 * 60L), now, 100
		)).isEqualTo(1);
		entityManager.clear();

		ExamSubmission failed = submissionRepository.findById(submission.getId()).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(failed.getGradingRetryCount()).isEqualTo(3);
		assertThat(failed.getGradingLeaseToken()).isNull();
		assertThat(failed.getGradingLeaseUntil()).isEqualTo(Instant.EPOCH);
	}

	@Test
	void cutoffRequeueProcessesAtMostOneHundredSubmissionsPerBatch() {
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		List<ExamSubmission> submissions = java.util.stream.IntStream.rangeClosed(1, 101)
			.mapToObj(attemptNo -> ExamSubmission.create(
				exam,
				learner,
				attemptNo,
				"cutoff-batch-" + attemptNo + "-" + UUID.randomUUID(),
				new BigDecimal("10.00"),
				now.minusSeconds(120 * 60L)
			))
			.toList();
		submissionRepository.saveAllAndFlush(submissions);
		jdbcTemplate.update(
			"update exam_submissions set updated_at = ? where exam_id = ?",
			Timestamp.from(now.minusSeconds(31 * 60L)),
			exam.getId()
		);
		entityManager.clear();

		assertThat(persistenceService.requeueExpiredSubmissions(
			now.minusSeconds(30 * 60L), now, 100
		)).hasSize(100);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from exam_submissions where exam_id = ? and grading_retry_count = 1",
			Integer.class,
			exam.getId()
		)).isEqualTo(100);
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from exam_submissions where exam_id = ? and grading_retry_count = 0",
			Integer.class,
			exam.getId()
		)).isEqualTo(1);
	}

	@Test
	void manualRegradeResetsRetryCountAndOnlyFailedSubmissionIsAccepted() {
		ExamSubmission failed = submission(1, Instant.parse("2026-08-03T00:00:00Z"));
		failed.failGrading();
		ReflectionTestUtils.setField(failed, "gradingRetryCount", 3);
		submissionRepository.saveAndFlush(failed);
		answerRepository.saveAndFlush(ExamAnswer.create(
			failed,
			questionRepository.findByExam_IdOrderByQuestionNo(exam.getId()).get(0),
			"answer",
			new BigDecimal("10.00")
		));

		var response = persistenceService.regradeFailedSubmission(exam.getId(), failed.getId());
		ExamSubmission requeued = submissionRepository.findById(failed.getId()).orElseThrow();

		assertThat(response.status()).isEqualTo(SubmissionStatus.SUBMITTED);
		assertThat(requeued.getGradingRetryCount()).isZero();
		assertThat(requeued.getGradingLeaseUntil()).isEqualTo(Instant.EPOCH);
		verify(gradingExecutor).execute(any(Runnable.class));
		Instant claimedAt = Instant.now();
		assertThat(persistenceService.claimGradingLease(
			failed.getId(), "manual-regrade", claimedAt, claimedAt.plusSeconds(300)
		)).isTrue();
		assertThat(persistenceService.applyAiGrading(
			failed.getId(),
			"manual-regrade",
			new ExamAiGradingOutcome(Map.of(
				"q1", new ExamAiGradingOutcome.GradedItem(
					new BigDecimal("9.00"), Verdict.PARTIAL, "regraded"
				)
			), false)
		)).isTrue();
		assertThat(studentExamService.mySubmission(
			learner.getId(), UserRole.LEARNER, exam.getId(), null
		).status()).isEqualTo(SubmissionStatus.GRADED);
		assertThatThrownBy(() -> persistenceService.regradeFailedSubmission(
			exam.getId(), failed.getId()
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.EXAM_ALREADY_SUBMITTED)
		);
	}

	@Test
	void manualRegradeAndNaturalRecoveryStillAllowOnlyOneLeaseClaim() {
		Instant now = Instant.parse("2026-08-03T02:00:00Z");
		ExamSubmission failed = submission(1, now.minusSeconds(120 * 60L));
		failed.failGrading();
		submissionRepository.saveAndFlush(failed);

		persistenceService.regradeFailedSubmission(exam.getId(), failed.getId());
		touch(failed.getId(), now.minusSeconds(31 * 60L));
		assertThat(persistenceService.requeueExpiredSubmissions(
			now.minusSeconds(30 * 60L), now, 100
		)).hasSize(1);

		assertThat(persistenceService.claimGradingLease(
			failed.getId(), "manual-worker", now.plusSeconds(1), now.plusSeconds(301)
		)).isTrue();
		assertThat(persistenceService.claimGradingLease(
			failed.getId(), "natural-worker", now.plusSeconds(1), now.plusSeconds(301)
		)).isFalse();
	}

	@Test
	void realBoundedQueueRejectionKeepsDbPendingAndANewWorkerRecoversIt() throws Exception {
		ThreadPoolTaskExecutor pool = new ExamGradingConfig().examGradingExecutor(
			new ExamGradingProperties(Duration.ofMinutes(5), new ExamGradingProperties.Executor(1, 1, 1)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CompletableFuture<Void> recovered = new CompletableFuture<>();
		ExamGradingDispatcher restartedDispatcher = restartedDispatcher(pool, Clock.systemUTC(), recovered);
		doAnswer(call -> {
			restartedDispatcher.dispatch(call.getArgument(0), call.getArgument(1));
			return null;
		}).when(dispatcher).dispatch(anyLong(), anyLong());
		FutureTask<Void> occupied = new FutureTask<>(() -> {
			entered.countDown();
			assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return null;
		});
		FutureTask<Void> queued = new FutureTask<>(() -> null);
		try {
			pool.execute(occupied);
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			pool.execute(queued);
			assertThat(pool.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
			var response = studentExamService.submit(learner.getId(), UserRole.LEARNER, exam.getId(),
				new SubmitExamRequest("real-saturation-" + UUID.randomUUID(),
					List.of(new ExamAnswerRequest("q1", "Fixed synthetic answer"))));
			assertThat(response.status()).isEqualTo(SubmissionStatus.SUBMITTED);
			var pending = submissionRepository.findById(response.submissionId()).orElseThrow();
			assertThat(pending.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
			assertThat(pending.getGradingLeaseToken()).isNull();
			assertThat(pending.getGradingLeaseUntil()).isEqualTo(Instant.EPOCH);
			assertThat(answerRepository.findBySubmission_IdOrderByQuestion_Id(response.submissionId()))
				.singleElement().satisfies(answer -> assertThat(answer.getAnswer()).isEqualTo("Fixed synthetic answer"));
			verifyNoInteractions(aiClient);
			release.countDown();
			occupied.get(5, TimeUnit.SECONDS);
			queued.get(5, TimeUnit.SECONDS);
			when(aiClient.grade(any())).thenReturn(successfulGrade());
			new ExamGradingRecoveryScheduler(persistenceService, restartedDispatcher, Clock.systemUTC()).recover();
			recovered.get(10, TimeUnit.SECONDS);
			assertThat(studentExamService.mySubmission(learner.getId(), UserRole.LEARNER, exam.getId(), null).status())
				.isEqualTo(SubmissionStatus.GRADED);
			verify(aiClient).grade(any());
		} finally {
			release.countDown();
			pool.shutdown();
		}
	}

	@Test
	void newSchedulerAndWorkerRecoverAnExpiredLeaseAndRejectTheOldWorkerResult() throws Exception {
		Instant now = Instant.now();
		var response = studentExamService.submit(learner.getId(), UserRole.LEARNER, exam.getId(),
			new SubmitExamRequest("synthetic-restart-" + UUID.randomUUID(),
				List.of(new ExamAnswerRequest("q1", "Original fixed answer"))));
		assertThat(persistenceService.claimGradingLease(response.submissionId(), "lost-worker",
			now.minusSeconds(600), now.minusSeconds(300))).isTrue();
		ThreadPoolTaskExecutor pool = new ExamGradingConfig().examGradingExecutor(
			new ExamGradingProperties(Duration.ofMinutes(5), new ExamGradingProperties.Executor(1, 1, 1)));
		CompletableFuture<Void> recovered = new CompletableFuture<>();
		Clock restartedClock = Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC);
		ExamGradingDispatcher restartedDispatcher = restartedDispatcher(pool, restartedClock, recovered);
		when(aiClient.grade(any())).thenReturn(successfulGrade());
		try {
			new ExamGradingRecoveryScheduler(persistenceService, restartedDispatcher, restartedClock).recover();
			recovered.get(10, TimeUnit.SECONDS);
			assertThat(submissionRepository.findById(response.submissionId()).orElseThrow().getStatus())
				.isEqualTo(SubmissionStatus.GRADED);
			assertThat(persistenceService.applyAiGrading(response.submissionId(), "lost-worker",
				new ExamAiGradingOutcome(Map.of("q1", new ExamAiGradingOutcome.GradedItem(
					BigDecimal.ZERO, Verdict.WRONG, "Stale worker result")), false))).isFalse();
			assertThat(submissionRepository.findById(response.submissionId()).orElseThrow().getScore())
				.isEqualByComparingTo("8.00");
			assertThat(answerRepository.findBySubmission_IdOrderByQuestion_Id(response.submissionId()))
				.singleElement().satisfies(answer -> assertThat(answer.getAnswer()).isEqualTo("Original fixed answer"));
			verify(aiClient).grade(any());
		} finally {
			pool.shutdown();
		}
	}

	private ExamGradingDispatcher restartedDispatcher(ThreadPoolTaskExecutor pool, Clock clock,
		CompletableFuture<Void> recovered) {
		ExamGradingWorker restarted = new ExamGradingWorker(persistenceService, aiGradingService, gradingProperties, clock) {
			@Override public void grade(Long submissionId) {
				try {
					super.grade(submissionId);
					recovered.complete(null);
				} catch (Throwable failure) {
					recovered.completeExceptionally(failure);
				}
			}
		};
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("restartedWorker", restarted);
		return new ExamGradingDispatcher(pool, beans.getBeanProvider(ExamGradingWorker.class));
	}

	private GradeResponse successfulGrade() {
		return new GradeResponse("1.0", exam.getId(), "SHORT", new BigDecimal("8.00"), BigDecimal.TEN,
			List.of(new GradeResponse.Item("q1", new BigDecimal("8.00"), BigDecimal.TEN, "PARTIAL", "Synthetic feedback")), null);
	}

	private ExamSubmission submission(int attemptNo, Instant submittedAt) {
		return submissionRepository.saveAndFlush(ExamSubmission.create(
			exam,
			learner,
			attemptNo,
			"recovery-" + attemptNo + "-" + UUID.randomUUID(),
			new BigDecimal("10.00"),
			submittedAt
		));
	}

	private void touch(Long submissionId, Instant updatedAt) {
		jdbcTemplate.update(
			"update exam_submissions set updated_at = ? where id = ?",
			Timestamp.from(updatedAt),
			submissionId
		);
		entityManager.clear();
	}

	private ExamQuestion shortQuestion(Exam owner) {
		return ExamQuestion.create(
			owner,
			1,
			ExamQuestionType.SHORT,
			new BigDecimal("10.00"),
			new ExamPublicQuestion("SHORT", List.of()),
			new ExamPrivateAnswer(null, null, null, "Reference", null, List.of()),
			"1.0"
		);
	}
}
