package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.mail.EmailService;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** Real repeatable-read and canonical migrations on a strictly owned, synthetic database. */
@EnabledIfEnvironmentVariable(named = "GUARDIAN_TURN_PERSISTENCE_MYSQL_URL",
	matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_turn_persistence_mysql_synthetic(?:\\?.*)?$")
@SpringBootTest(properties = {
	"spring.datasource.username=root", "spring.datasource.password=", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
	"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=true",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/turn-persistence-consent-mysql",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging", "edupilot.ai.quota.enabled=false",
	"edupilot.guardian.team.recovery-initial-delay-ms=3600000",
	// These notices and durations are synthetic fixtures, not operating-policy decisions.
	"edupilot.guardian.team.enabled=true", "edupilot.guardian.team.policy-confirmed=true",
	"edupilot.guardian.team.portal-base-url=https://synthetic.example.test",
	"edupilot.guardian.team.notice-url=https://synthetic.example.test/notice", "edupilot.guardian.team.notice-version=synthetic-1",
	"edupilot.guardian.team.notice-digest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
	"edupilot.guardian.team.collection-items-text=Synthetic test information",
	"edupilot.guardian.team.purposes-text=Synthetic test verification", "edupilot.guardian.team.retention-text=Synthetic test retention notice",
	"edupilot.guardian.team.refusal-text=Synthetic test refusal notice", "edupilot.guardian.team.reply-contact=reviewer@synthetic.example.test",
	"edupilot.guardian.team.reply-channel=EMAIL_REPLY", "edupilot.guardian.team.reviewer-ids=1",
	"edupilot.guardian.team.required-scopes=SERVICE", "edupilot.guardian.team.optional-ai-scope=EXTERNAL_AI",
	"edupilot.guardian.team.optional-consent-text=Synthetic test external AI consent",
	"edupilot.guardian.team.link-ttl=PT1H", "edupilot.guardian.team.request-ttl=PT96H",
	"edupilot.guardian.team.approved-evidence-retention=P30D", "edupilot.guardian.team.approval-validity=P30D"
})
@ActiveProfiles("jpa-context")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TurnPersistenceConsentMysqlTest {
	private static final String DATABASE = "guardian_turn_persistence_mysql_synthetic";
	private static final String DATADIR = "C:/Users/russe/Documents/Codex/2026-10-03/task-2/mysql-isolated";
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private TurnPersistenceService persistence;
	@Autowired private TurnClaimService claims;
	@Autowired private GuardianTeamProperties policy;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Flyway migrations;
	@PersistenceContext private EntityManager entities;
	@MockitoBean private Clock clock;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailService mail;
	@MockitoBean private ConversationSummaryDispatcher summaries;
	private Instant now;
	private User child;
	private LearningSession session;
	private String requestId;

	@DynamicPropertySource
	static void ownedSyntheticDatabase(DynamicPropertyRegistry registry) throws SQLException {
		String url = System.getenv("GUARDIAN_TURN_PERSISTENCE_MYSQL_URL");
		assertThat(url).matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_turn_persistence_mysql_synthetic(?:\\?.*)?$");
		try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:33316/", "root", "");
			var sql = connection.createStatement()) {
			try (var identity = sql.executeQuery("select @@port,@@datadir")) {
				assertThat(identity.next()).isTrue();
				assertThat(identity.getInt(1)).isEqualTo(33316);
				assertThat(identity.getString(2).replace('\\', '/').replaceAll("/+$", "")).isEqualTo(DATADIR);
			}
			sql.execute("create database if not exists " + DATABASE);
		}
		registry.add("spring.datasource.url", () -> url);
	}

	@BeforeEach
	void committedSyntheticAccountAndClaim() {
		now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		when(clock.instant()).thenReturn(now);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(DATABASE);
		assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
		assertThat(jdbc.queryForObject("select @@datadir", String.class).replace('\\', '/').replaceAll("/+$", "")).isEqualTo(DATADIR);
		assertThat(migrations.validateWithResult().validationSuccessful).isTrue();
		assertThat(migrations.info().current().getVersion().getVersion()).isEqualTo("63");
		assertThat(policy.ready()).isTrue();
		child = transaction().execute(status -> {
			User account = User.create("synthetic-turn-" + UUID.randomUUID() + "@example.test", "!synthetic", "Synthetic child");
			account.recordSignupDateOfBirth(LocalDate.of(2013, 1, 1));
			account.verifyEmail(now.minusSeconds(60));
			approve(account);
			account = users.saveAndFlush(account);
			LearningMaterial material = LearningMaterial.create(account, "Synthetic material", "materials/" + UUID.randomUUID() + ".pdf");
			material.markReady(1);
			material = materials.saveAndFlush(material);
			session = sessions.saveAndFlush(LearningSession.create(account, material));
			return account;
		});
		requestId = "synthetic-request-" + UUID.randomUUID();
		claims.claim(child.getId(), session.getId(), requestId);
	}

	@ParameterizedTest
	@EnumSource(ResultType.class)
	void alreadyReadLockedOlderSnapshotCannotSaveAResponseAfterCommittedReapproval(ResultType type) throws Exception {
		long originalEpoch = child.getGuardianConsentEpoch();
		AtomicLong committedEpoch = new AtomicLong(-1);
		AtomicLong refreshedEpoch = new AtomicLong(-1);
		AtomicReference<LockModeType> finalLock = new AtomicReference<>();
		try {
			Throwable failure;
			try (var executor = Executors.newSingleThreadExecutor()) {
				failure = catchThrowable(() -> transaction().executeWithoutResult(status -> {
					assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
					User stale = users.findById(child.getId()).orElseThrow();
					assertThat(stale.getGuardianConsentEpoch()).isEqualTo(originalEpoch);
					try { committedEpoch.set(executor.submit(this::revokeAndReapprove).get(10, TimeUnit.SECONDS)); }
					catch (Exception changed) { throw new IllegalStateException("Synthetic consent change did not commit", changed); }
					assertThat(committedEpoch.get()).isEqualTo(originalEpoch + 2);
					assertThat(users.findByIdForBusinessAccess(child.getId()).orElseThrow()).isSameAs(stale);
					assertThat(entities.getLockMode(stale)).isEqualTo(LockModeType.PESSIMISTIC_READ);
					// Locking lookup retains this already-managed entity's older snapshot.
					assertThat(stale.getGuardianConsentEpoch()).isEqualTo(originalEpoch);
					try { persist(type, originalEpoch); }
					finally {
						refreshedEpoch.set(stale.getGuardianConsentEpoch());
						finalLock.set(entities.getLockMode(stale));
					}
				}));
			}
			System.out.println("SYNTHETIC_TURN_RR result=" + type + " originalEpoch=" + originalEpoch
				+ " committedEpoch=" + committedEpoch.get() + " refreshedEpoch=" + refreshedEpoch.get()
				+ " lock=" + finalLock.get() + " staleMessageCount=" + messageCount()
				+ " failure=" + (failure instanceof BusinessException business ? business.errorCode().code() : failure));
			assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
				business -> assertThat(business.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
			assertThat(refreshedEpoch.get()).isEqualTo(committedEpoch.get());
			assertThat(finalLock.get()).isEqualTo(LockModeType.PESSIMISTIC_READ);
			assertThat(messageCount()).isZero();
			assertThat(jdbc.queryForObject("select count(*) from session_page_records where session_id=?", Integer.class, session.getId())).isZero();
			verifyNoInteractions(summaries, ai, mail);
			UserBusinessAccessState current = users.findBusinessAccessStateById(child.getId()).orElseThrow();
			assertThat(current.aiEligibilityFailure(Clock.fixed(now, ZoneOffset.UTC), policy.ready(), policy.configurationDigest())).isNull();
			PersistedTurn fresh = persist(type, current.guardianConsentEpoch());
			assertThat(fresh.messages()).hasSize(1);
			assertThat(messageCount()).isEqualTo(1);
			System.out.println("SYNTHETIC_TURN_RR_FRESH result=" + type + " epoch=" + current.guardianConsentEpoch() + " messageCount=" + messageCount());
			verifyNoInteractions(ai, mail);
		} finally { claims.release(session.getId(), requestId); }
	}

	private long revokeAndReapprove() {
		return transaction().execute(status -> {
			User current = users.findByIdForUpdate(child.getId()).orElseThrow();
			current.clearGuardianTeamApproval(false);
			approve(current);
			users.flush();
			return current.getGuardianConsentEpoch();
		});
	}

	private void approve(User account) {
		account.recordGuardianTeamApproval(now.plusSeconds(3600), true);
		account.recordGuardianTeamPolicyDigest(policy.configurationDigest());
	}

	private PersistedTurn persist(ResultType type, long epoch) {
		return type == ResultType.CANCELLED
			? persistence.persistCancelled(child.getId(), UserRole.LEARNER, epoch, session.getId(), requestId, "synthetic-turn", "Synthetic partial response")
			: persistence.persist(child.getId(), UserRole.LEARNER, epoch, session.getId(), requestId,
				TurnEventType.EXPLAIN_CURRENT_PAGE, null, null, false,
				new io.edupilot.ai.dto.TurnResponse("1.0", "synthetic-turn", "EXPLAIN", List.of(),
					List.of(Map.of("messageType", "EXPLANATION", "content", "Synthetic AI response")),
					Map.of("pageStatus", "EXPLAINED"), List.of(), null, List.of(), null, null));
	}

	private TransactionTemplate transaction() {
		TransactionTemplate transaction = new TransactionTemplate(transactions);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return transaction;
	}

	private int messageCount() {
		return jdbc.queryForObject("select count(*) from chat_messages where session_id=?", Integer.class, session.getId());
	}

	private enum ResultType { COMPLETED, CANCELLED }
}
