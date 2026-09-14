package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.ai.AiClient;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ManualScoreAdjustmentRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-manual-score;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-manual-score"
	}
)
@ActiveProfiles("jpa-context")
class ExamManualScoreAdjustmentJpaTest {

	private static final Instant ADJUSTED_AT = Instant.parse("2026-09-10T09:00:00Z");

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private ExamAnswerRepository answerRepository;
	@Autowired private InstructorExamService instructorExamService;
	@Autowired private StudentExamService studentExamService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;

	@MockitoBean private AiClient aiClient;
	@MockitoBean private Clock clock;
	@MockitoBean(name = "examGradingExecutor")
	private ThreadPoolTaskExecutor gradingExecutor;
	@MockitoBean private ExamGradingRecoveryScheduler recoveryScheduler;

	private User instructor;
	private User learner;
	private Exam exam;
	private ExamSubmission submission;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(ADJUSTED_AT);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		instructor = userRepository.save(User.create(
			"manual-instructor-" + suffix + "@example.com",
			"hash",
			"Instructor",
			UserRole.INSTRUCTOR
		));
		learner = userRepository.save(User.create(
			"manual-learner-" + suffix + "@example.com",
			"hash",
			"Learner",
			UserRole.LEARNER
		));
		Classroom classroom = classroomRepository.save(Classroom.create(
			instructor,
			"Manual score classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"MS" + suffix
		));
		memberRepository.save(ClassroomMember.create(
			classroom, learner, ADJUSTED_AT.minusSeconds(3_600)
		));
		exam = Exam.create(classroom, 1, "Manual score exam", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(ADJUSTED_AT.minusSeconds(3_600));
		exam = examRepository.saveAndFlush(exam);
		ExamQuestion question = questionRepository.saveAndFlush(ExamQuestion.create(
			exam,
			1,
			ExamQuestionType.SHORT,
			new BigDecimal("10.00"),
			new ExamPublicQuestion("Explain", List.of()),
			new ExamPrivateAnswer(
				null, null, "Explanation", "Reference", null, List.of()
			),
			"1.0"
		));
		submission = submissionRepository.saveAndFlush(ExamSubmission.create(
			exam,
			learner,
			1,
			"manual-score-request",
			new BigDecimal("10.00"),
			ADJUSTED_AT.minusSeconds(120)
		));
		ExamAnswer answer = ExamAnswer.create(
			submission, question, "private learner answer", new BigDecimal("10.00")
		);
		answer.recordGrade(new BigDecimal("4.00"), Verdict.PARTIAL, "AI feedback");
		answerRepository.saveAndFlush(answer);
		submission.complete(
			new BigDecimal("4.00"), new BigDecimal("40.00"), ADJUSTED_AT.minusSeconds(60)
		);
		submissionRepository.saveAndFlush(submission);
		entityManager.clear();
	}

	@Test
	void persistsManualScoreAndReturnsOnlyEffectiveScoreToLearnerWithoutNotification() {
		Long notificationsBefore = jdbcTemplate.queryForObject(
			"select count(*) from notifications", Long.class
		);

		var instructorResponse = instructorExamService.adjustAnswerScore(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			exam.getId(),
			submission.getId(),
			"q1",
			new ManualScoreAdjustmentRequest(new BigDecimal("8.00"))
		);
		entityManager.clear();

		ExamAnswer storedAnswer = answerRepository
			.findBySubmission_IdOrderByQuestion_Id(submission.getId())
			.getFirst();
		ExamSubmission storedSubmission = submissionRepository
			.findById(submission.getId())
			.orElseThrow();
		assertThat(storedAnswer.getScore()).isEqualByComparingTo("4.00");
		assertThat(storedAnswer.getManualScore()).isEqualByComparingTo("8.00");
		assertThat(storedAnswer.getAdjustedBy()).isEqualTo(instructor.getId());
		assertThat(storedAnswer.getAdjustedAt()).isEqualTo(ADJUSTED_AT);
		assertThat(storedAnswer.getVerdict()).isEqualTo(Verdict.PARTIAL);
		assertThat(storedSubmission.getScore()).isEqualByComparingTo("8.00");
		assertThat(storedSubmission.getNormalizedScore()).isEqualByComparingTo("80.00");
		assertThat(instructorResponse.items().getFirst().manualScore())
			.isEqualByComparingTo("8.00");

		var learnerResponse = studentExamService.mySubmission(
			learner.getId(), UserRole.LEARNER, exam.getId(), null
		);
		assertThat(learnerResponse.reviewAvailable()).isTrue();
		assertThat(learnerResponse.score()).isEqualByComparingTo("8.00");
		assertThat(learnerResponse.items().getFirst().score())
			.isEqualByComparingTo("8.00");
		String learnerJson = new ObjectMapper()
			.findAndRegisterModules()
			.valueToTree(learnerResponse)
			.toString();
		assertThat(learnerJson)
			.doesNotContain("manualScore")
			.doesNotContain("adjustedBy")
			.doesNotContain("adjustedAt");
		assertThat(jdbcTemplate.queryForObject(
			"select count(*) from notifications", Long.class
		)).isEqualTo(notificationsBefore);
	}
}
