package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomStudentResponse;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.quiz.GradingResult;
import io.edupilot.quiz.Quiz;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmission;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.quiz.QuizType;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.QaMessage;
import io.edupilot.session.QaMessageRepository;
import io.edupilot.session.QaThread;
import io.edupilot.session.QaThreadRepository;
import io.edupilot.session.SessionPageRecordRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:classroom-student-metrics;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.generate_statistics=true",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/classroom-student-metrics"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class ClassroomStudentJpaTest {

	private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ClassroomWeekRepository weekRepository;
	@Autowired private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private LearningSessionRepository sessionRepository;
	@Autowired private QaThreadRepository qaThreadRepository;
	@Autowired private ChatMessageRepository chatMessageRepository;
	@Autowired private QaMessageRepository qaMessageRepository;
	@Autowired private QuizRepository quizRepository;
	@Autowired private QuizSubmissionRepository quizSubmissionRepository;
	@Autowired private SessionPageRecordRepository pageRecordRepository;
	@Autowired private ClassroomStudentService studentService;
	@Autowired private ClassroomAnalyticsService analyticsService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@MockitoBean private FileStorage fileStorage;
	@MockitoBean private Clock clock;

	@BeforeEach
	void createPageRecordTable() {
		when(clock.instant()).thenReturn(NOW);
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS session_page_records (
			    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
			    session_id BIGINT NOT NULL,
			    page_number INT NOT NULL,
			    explained_at DATETIME(6) NOT NULL,
			    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
			    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
			    CONSTRAINT uk_student_metrics_page UNIQUE (session_id, page_number)
			)
			""");
	}

	@Test
	void privateWeekMaterialsContributeToStudentAndAnalyticsMetrics() {
		User instructor = user("instructor@example.com", "Instructor", UserRole.INSTRUCTOR);
		Classroom classroom = classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			"Metrics",
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 8, 31),
			ClassroomColor.BLUE,
			null,
			"AAAA-BBBB"
		));
		ClassroomWeek week = weekRepository.saveAndFlush(ClassroomWeek.create(
			classroom,
			1,
			"Week 1",
			null,
			ClassroomWeekStatus.PRIVATE,
			1
		));
		LearningMaterial material = material(instructor);
		weekMaterialRepository.saveAndFlush(ClassroomWeekMaterial.create(
			week,
			material,
			NOW
		));

		User charlie = learner(classroom, "charlie@example.com", "Charlie", NOW);
		User alice = learner(classroom, "alice@example.com", "Alice", NOW.minusSeconds(1));
		User bob = learner(classroom, "bob@example.com", "Bob", NOW.minusSeconds(2));
		LearningSession charlieSession = sessionRepository.saveAndFlush(
			LearningSession.create(charlie, material)
		);
		LearningSession aliceSession = sessionRepository.saveAndFlush(
			LearningSession.create(alice, material)
		);
		for (int page = 1; page <= 5; page++) {
			pageRecordRepository.upsertExplainedPage(charlieSession.getId(), page, NOW);
		}
		for (int page = 1; page <= 2; page++) {
			pageRecordRepository.upsertExplainedPage(aliceSession.getId(), page, NOW);
		}
		addQuestion(
			charlieSession,
			"boundary",
			"boundary-request",
			NOW.minus(Duration.ofDays(7))
		);
		addQuestion(
			charlieSession,
			"old",
			"old-request",
			NOW.minus(Duration.ofDays(8))
		);
		addQuestion(
			aliceSession,
			"recent",
			"recent-request",
			NOW.minus(Duration.ofDays(1))
		);
		Quiz submittedQuiz = quizRepository.saveAndFlush(Quiz.create(
			charlieSession,
			5,
			"Submitted quiz",
			5,
			5,
			QuizType.MCQ,
			List.of(),
			List.of(),
			"1.0"
		));
		quizSubmissionRepository.saveAndFlush(quizSubmission(
			submittedQuiz,
			charlie,
			"student-list-attempt-1",
			1,
			"40"
		));
		quizSubmissionRepository.saveAndFlush(quizSubmission(
			submittedQuiz,
			charlie,
			"student-list-attempt-2",
			2,
			"80"
		));
		entityManager.flush();
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
			.getStatistics();
		statistics.clear();

		var response = studentService.list(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.getId(),
			0,
			20,
			null,
			null
		);
		long queryCount = statistics.getQueryExecutionCount();
		Map<String, ClassroomStudentResponse> byName = response.items().stream()
			.collect(Collectors.toMap(ClassroomStudentResponse::name, item -> item));

		assertThat(response.items()).hasSize(3);
		assertThat(byName.get("Charlie").averageProgressRate()).isEqualTo(50);
		assertThat(byName.get("Charlie").aiQuestionCountLast7Days()).isEqualTo(1);
		assertThat(byName.get("Charlie").quizSubmissionCount()).isEqualTo(1);
		assertThat(byName.get("Alice").averageProgressRate()).isEqualTo(20);
		assertThat(byName.get("Alice").aiQuestionCountLast7Days()).isEqualTo(1);
		assertThat(byName.get("Alice").quizSubmissionCount()).isZero();
		assertThat(byName.get("Bob").averageProgressRate()).isZero();
		assertThat(byName.get("Bob").aiQuestionCountLast7Days()).isZero();
		assertThat(byName.get("Bob").quizSubmissionCount()).isZero();
		assertThat(queryCount).isLessThanOrEqualTo(6);

		var analytics = analyticsService.getAnalytics(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.getId()
		);
		assertThat(analytics.averageProgressRate()).isEqualTo(23);
		assertThat(analytics.aiQuestionCountLast7Days()).isEqualTo(2);
		assertThat(Math.round(response.items().stream()
			.mapToInt(ClassroomStudentResponse::averageProgressRate)
			.average()
			.orElse(0))).isEqualTo(analytics.averageProgressRate());
		assertThat(response.items().stream()
			.mapToLong(ClassroomStudentResponse::aiQuestionCountLast7Days)
			.sum()).isEqualTo(analytics.aiQuestionCountLast7Days());
	}

	private User learner(
		Classroom classroom,
		String email,
		String name,
		Instant joinedAt
	) {
		User learner = user(email, name, UserRole.LEARNER);
		memberRepository.saveAndFlush(ClassroomMember.create(
			classroom,
			learner,
			joinedAt
		));
		return learner;
	}

	private User user(String email, String name, UserRole role) {
		return userRepository.saveAndFlush(User.create(
			email,
			"hash",
			name,
			role
		));
	}

	private LearningMaterial material(User owner) {
		LearningMaterial material = LearningMaterial.create(
			owner,
			"Material",
			"materials/student-metrics.pdf"
		);
		material.markReady(10);
		return materialRepository.saveAndFlush(material);
	}

	private void addQuestion(
		LearningSession session,
		String content,
		String requestId,
		Instant createdAt
	) {
		QaThread thread = qaThreadRepository.saveAndFlush(QaThread.start(session));
		ChatMessage chatMessage = chatMessageRepository.saveAndFlush(
			ChatMessage.user(session, content, requestId)
		);
		QaMessage message = qaMessageRepository.saveAndFlush(
			QaMessage.from(thread, chatMessage)
		);
		jdbcTemplate.update(
			"UPDATE qa_messages SET created_at = ? WHERE id = ?",
			Timestamp.from(createdAt),
			message.getId()
		);
	}

	private QuizSubmission quizSubmission(
		Quiz quiz,
		User student,
		String requestId,
		int attemptNo,
		String score
	) {
		QuizSubmission submission = QuizSubmission.create(
			quiz,
			student,
			requestId,
			List.of(),
			new GradingResult(
				"1.0",
				new BigDecimal(score),
				new BigDecimal("100"),
				List.of()
			),
			new BigDecimal(score).compareTo(new BigDecimal("60")) >= 0
		);
		ReflectionTestUtils.setField(submission, "attemptNo", attemptNo);
		return submission;
	}
}
