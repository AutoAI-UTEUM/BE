package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.ai.AiClient;
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

	@MockitoBean private AiClient aiClient;
	@MockitoBean(name = "examGradingExecutor")
	private ThreadPoolTaskExecutor gradingExecutor;
	@MockitoBean private ExamGradingRecoveryScheduler recoveryScheduler;

	private User learner;
	private Exam exam;

	@BeforeEach
	void setUp() {
		answerRepository.deleteAll();
		submissionRepository.deleteAll();
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		User instructor = userRepository.save(User.create(
			"recovery-instructor-" + suffix + "@example.com",
			"hash",
			"Instructor",
			UserRole.INSTRUCTOR
		));
		learner = userRepository.save(User.create(
			"recovery-learner-" + suffix + "@example.com",
			"hash",
			"Learner",
			UserRole.LEARNER
		));
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
