package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.VerifiedTestUsers;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:connected-sse-revocation;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/connected-sse-revocation",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging"
})
@ActiveProfiles("jpa-context")
class SessionStreamRevocationJpaTest {
	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url = System.getenv("SSE_MYSQL_URL");
		if (url == null || url.isBlank()) return;
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/runtime_stream_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("SSE tests require the disposable loopback database");
		}
		settings.add("spring.datasource.url", () -> url);
		settings.add("spring.datasource.username", () -> "root");
		settings.add("spring.datasource.password", () -> "");
		settings.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private MaterialAccessService materialAccess;
	@Autowired private SessionStreamAccessGuard streamAccess;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private AiClient ai;
	private SessionStreamService streams;
	private User learner;
	private LearningSession session;
	private LearningMaterial material;
	private ClassroomMember member;
	private ClassroomWeekMaterial link;
	private final RecordingEmitter emitter = new RecordingEmitter();

	@BeforeEach
	void setup() {
		if (System.getenv("SSE_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("runtime_stream_synthetic");
		}
		User instructor = user(UserRole.INSTRUCTOR);
		learner = user(UserRole.LEARNER);
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(instructor, "Synthetic classroom",
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		member = members.saveAndFlush(ClassroomMember.create(classroom, learner, Instant.now()));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(classroom, 1, "Synthetic week", null,
			ClassroomWeekStatus.PUBLISHED, 1));
		material = LearningMaterial.create(instructor, "Synthetic PDF", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		material = materials.saveAndFlush(material);
		link = links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		session = sessions.saveAndFlush(LearningSession.create(learner, material));
		streams = new SessionStreamService(sessions, materialAccess, streamAccess, () -> emitter);
	}

	@AfterEach
	void shutdown() {
		if (streams != null) streams.shutdown();
	}

	@ParameterizedTest
	@EnumSource(Revocation.class)
	void committedRevocationStopsTheAlreadyConnectedStreamBeforeTheNextPayload(Revocation revocation) {
		AiStreamCancellation upstream = new AiStreamCancellation();
		SessionStreamConnection connection = connect(upstream);
		connection.send(TurnStreamEvent.contentDelta("Synthetic authorized content"));
		int delivered = emitter.deliveries.get();

		revoke(revocation);

		assertThatThrownBy(() -> connection.send(TurnStreamEvent.contentDelta("Synthetic revoked content")))
			.isInstanceOf(AiClientException.class);
		assertThat(emitter.deliveries.get()).isEqualTo(delivered);
		assertThat(connection.isClosed()).isTrue();
		assertThat(upstream.isCancelled()).isTrue();
		assertThat(upstream.isUserCancelled()).isFalse();
		assertThat(streams.beginTurn(learner.getId(), session.getId(), "synthetic-new-turn", new AiStreamCancellation())).isEmpty();
		verifyNoInteractions(ai);
	}

	@Test
	void revocationIsNotHiddenByTheCallerTransactionsPreviouslyReadAccountAndSession() throws Exception {
		AiStreamCancellation upstream = new AiStreamCancellation();
		SessionStreamConnection connection = connect(upstream);
		try (var executor = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				assertThat(users.findById(learner.getId()).orElseThrow().isActive()).isTrue();
				assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.ACTIVE);
				try {
					executor.submit(() -> revoke(Revocation.ACCOUNT_DELETED)).get(10, TimeUnit.SECONDS);
				} catch (Exception failure) {
					throw new IllegalStateException("Synthetic committed revocation failed", failure);
				}
				assertThatThrownBy(() -> connection.send(TurnStreamEvent.contentDelta("Synthetic revoked content")))
					.isInstanceOf(AiClientException.class);
				transaction.setRollbackOnly();
			});
		}
		assertThat(upstream.isCancelled()).isTrue();
		assertThat(connection.isClosed()).isTrue();
	}

	@Test
	void authorizedCompletionCanStillDeliverTheTerminalEventAfterSessionCompletion() {
		AiStreamCancellation upstream = new AiStreamCancellation();
		SessionStreamConnection connection = connect(upstream);
		jdbc.update("update learning_sessions set status='COMPLETED' where id=?", session.getId());

		connection.sendCompleted("synthetic-request", mock(TurnResponse.class));

		assertThat(emitter.deliveries.get()).isEqualTo(2); // ready, completed
		assertThat(connection.isClosed()).isTrue();
		assertThat(upstream.isCancelled()).isFalse();
	}

	private SessionStreamConnection connect(AiStreamCancellation upstream) {
		streams.connect(learner.getId(), session.getId());
		return streams.beginTurn(learner.getId(), session.getId(), "synthetic-request", upstream).orElseThrow();
	}

	private User user(UserRole role) {
		return users.saveAndFlush(VerifiedTestUsers.legacyVerified(User.create(
			"synthetic-" + UUID.randomUUID() + "@example.test", "!synthetic", "Synthetic user", role)));
	}

	private void revoke(Revocation revocation) {
		switch (revocation) {
			case ACCOUNT_DELETED -> jdbc.update("update users set status='DELETED' where id=?", learner.getId());
			case ACCOUNT_SUSPENDED -> jdbc.update("update users set status='SUSPENDED' where id=?", learner.getId());
			case ROLE_CHANGED -> jdbc.update("update users set role='INSTRUCTOR' where id=?", learner.getId());
			case MEMBERSHIP_REMOVED -> members.deleteById(member.getId());
			case LINK_REMOVED -> links.deleteById(link.getId());
			case MATERIAL_DELETED -> jdbc.update("update learning_materials set status='DELETED' where id=?", material.getId());
			case SESSION_DELETED -> jdbc.update("update learning_sessions set status='DELETED' where id=?", session.getId());
		}
	}

	private enum Revocation { ACCOUNT_DELETED, ACCOUNT_SUSPENDED, ROLE_CHANGED, MEMBERSHIP_REMOVED, LINK_REMOVED, MATERIAL_DELETED, SESSION_DELETED }

	private static final class RecordingEmitter extends SseEmitter {
		private final AtomicInteger deliveries = new AtomicInteger();
		@Override public void send(SseEventBuilder event) throws IOException {
			deliveries.incrementAndGet();
		}
	}
}
