package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.ai.AiClient;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

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
	@Autowired private ExamSubmissionPersistenceService persistenceService;
	@Autowired private StudentExamService studentExamService;

	@MockitoBean private AiClient aiClient;
	@MockitoBean(name = "examGradingExecutor")
	private ThreadPoolTaskExecutor gradingExecutor;
	@MockitoBean private ExamGradingRecoveryScheduler recoveryScheduler;

	private User learner;
	private Exam exam;

	@BeforeEach
	void setUp() {
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
	void cutoffProcessesOnlyOneHundredSubmissionsPerBatch() {
		Instant now = Instant.now();
		List<ExamSubmission> submissions = java.util.stream.IntStream.rangeClosed(1, 101)
			.mapToObj(attemptNo -> ExamSubmission.create(
				exam,
				learner,
				attemptNo,
				"cutoff-batch-" + attemptNo + "-" + UUID.randomUUID(),
				new BigDecimal("10.00"),
				now.minusSeconds(31 * 60L)
			))
			.toList();
		submissionRepository.saveAllAndFlush(submissions);

		int firstBatch = persistenceService.failExpiredSubmissions(
			now.minusSeconds(30 * 60L), now, 100
		);
		List<ExamSubmission> afterFirstBatch = submissionRepository
			.findByExam_Id(exam.getId(), Pageable.unpaged())
			.getContent();

		assertThat(firstBatch).isEqualTo(100);
		assertThat(afterFirstBatch).filteredOn(
			submission -> submission.getStatus() == SubmissionStatus.GRADING_FAILED
		).hasSize(100);
		assertThat(afterFirstBatch).filteredOn(
			submission -> submission.getStatus() == SubmissionStatus.SUBMITTED
		).hasSize(1);

		assertThat(persistenceService.failExpiredSubmissions(
			now.minusSeconds(30 * 60L), now.plusSeconds(1), 100
		)).isEqualTo(1);
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
