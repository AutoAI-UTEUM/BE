package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.storage.FileStorage;
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
		"spring.datasource.url=jdbc:h2:mem:classroom-analytics;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
		"edupilot.storage.root-directory=build/test-storage/classroom-analytics"
	}
)
@ActiveProfiles("jpa-context")
@Transactional
class ClassroomAnalyticsJpaTest {

	private static final Instant NOW = Instant.parse("2026-08-04T03:00:00Z");

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ClassroomRepository classroomRepository;
	@Autowired
	private ClassroomMemberRepository memberRepository;
	@Autowired
	private ClassroomWeekRepository weekRepository;
	@Autowired
	private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired
	private LearningMaterialRepository materialRepository;
	@Autowired
	private LearningSessionRepository sessionRepository;
	@Autowired
	private QaThreadRepository qaThreadRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private QaMessageRepository qaMessageRepository;
	@Autowired
	private SessionPageRecordRepository pageRecordRepository;
	@Autowired
	private ClassroomAnalyticsService analyticsService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@MockitoBean
	private FileStorage fileStorage;
	@MockitoBean
	private Clock clock;

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
			    CONSTRAINT uk_test_session_page UNIQUE (session_id, page_number)
			)
			""");
	}

	@Test
	void analyticsUsesConstantQueryCountAndExcludesOtherClassroomData() {
		User instructor = user("instructor@example.com", UserRole.INSTRUCTOR);
		User otherInstructor = user("other-instructor@example.com", UserRole.INSTRUCTOR);
		Classroom classroom = classroom(instructor, "Analytics", "AAAA-BBBB");
		Classroom otherClassroom = classroom(
			otherInstructor,
			"Other",
			"CCCC-DDDD"
		);
		ClassroomWeek week = weekRepository.saveAndFlush(
			ClassroomWeek.create(
				classroom, 1, "Week 1", null, ClassroomWeekStatus.PUBLISHED, 1
			)
		);
		ClassroomWeek otherWeek = weekRepository.saveAndFlush(
			ClassroomWeek.create(
				otherClassroom, 1, "Other Week", null, ClassroomWeekStatus.PUBLISHED, 1
			)
		);

		List<LearningMaterial> materials = new ArrayList<>();
		List<LearningSession> sessions = new ArrayList<>();
		for (int index = 1; index <= 5; index++) {
			User learner = user("learner" + index + "@example.com", UserRole.LEARNER);
			memberRepository.save(ClassroomMember.create(classroom, learner, NOW));
			LearningMaterial material = material(
				instructor,
				"Material " + index,
				"materials/analytics-" + index + ".pdf"
			);
			materials.add(material);
			weekMaterialRepository.save(ClassroomWeekMaterial.create(
				week,
				material,
				NOW
			));
			LearningSession session = sessionRepository.save(
				LearningSession.create(learner, material)
			);
			sessions.add(session);
		}

		User otherLearner = user("other-learner@example.com", UserRole.LEARNER);
		memberRepository.save(ClassroomMember.create(
			otherClassroom,
			otherLearner,
			NOW
		));
		LearningMaterial otherMaterial = material(
			otherInstructor,
			"Other Material",
			"materials/other.pdf"
		);
		weekMaterialRepository.save(ClassroomWeekMaterial.create(
			otherWeek,
			otherMaterial,
			NOW
		));
		LearningSession otherSession = sessionRepository.save(
			LearningSession.create(otherLearner, otherMaterial)
		);
		entityManager.flush();
		for (LearningSession session : sessions) {
			pageRecordRepository.upsertExplainedPage(session.getId(), 1, NOW);
		}
		pageRecordRepository.upsertExplainedPage(otherSession.getId(), 1, NOW);
		addQuestion(sessions.get(0), "classroom question");
		addQuestion(otherSession, "other question");
		entityManager.flush();
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
			.getStatistics();
		statistics.clear();

		var response = analyticsService.getAnalytics(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.getId()
		);

		assertThat(response.learnerCount()).isEqualTo(5);
		assertThat(response.materials()).hasSize(5)
			.extracting(item -> item.title())
			.doesNotContain("Other Material");
		assertThat(response.questionsByPage()).singleElement()
			.satisfies(count -> {
				assertThat(count.materialId()).isEqualTo(materials.get(0).getId());
				assertThat(count.questionCount()).isEqualTo(1);
			});
		assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(6);
	}

	private User user(String email, UserRole role) {
		return userRepository.saveAndFlush(User.create(
			email,
			"hash",
			email,
			role
		));
	}

	private Classroom classroom(User instructor, String name, String inviteCode) {
		return classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			name,
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 8, 31),
			ClassroomColor.BLUE,
			null,
			inviteCode
		));
	}

	private LearningMaterial material(
		User owner,
		String title,
		String storageKey
	) {
		LearningMaterial material = LearningMaterial.create(owner, title, storageKey);
		material.markReady(10);
		return materialRepository.saveAndFlush(material);
	}

	private void addQuestion(LearningSession session, String content) {
		QaThread thread = qaThreadRepository.save(QaThread.start(session));
		ChatMessage chatMessage = chatMessageRepository.save(
			ChatMessage.user(session, content, "request-" + session.getId())
		);
		qaMessageRepository.save(QaMessage.from(thread, chatMessage));
	}
}
