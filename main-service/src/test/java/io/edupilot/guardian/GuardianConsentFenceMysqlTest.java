package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.hibernate.LockMode;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** Opt-in canonical MySQL migrations, disposable synthetic approvals and outputs only. */
@EnabledIfEnvironmentVariable(named = "GUARDIAN_CONSENT_FENCE_MYSQL_URL",
	matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_consent_fence_mysql_synthetic(?:\\?.*)?$")
@SpringBootTest(properties = {
	"spring.datasource.username=root", "spring.datasource.password=", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
	"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false",
	"logging.level.org.hibernate.SQL=DEBUG",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-consent-fence-mysql",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging", "edupilot.ai.quota.enabled=false",
	"edupilot.guardian.team.recovery-initial-delay-ms=3600000",
	// Notices and durations are synthetic test data, not operating-policy decisions.
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
class GuardianConsentFenceMysqlTest {
	private static final String DATABASE = "guardian_consent_fence_mysql_synthetic";
	private static final String DATADIR = "C:/Users/russe/Documents/Codex/2026-10-03/task-2/mysql-isolated";
	@Autowired private UserRepository users;
	@Autowired private GuardianConsentFence fence;
	@Autowired private GuardianTeamProperties policy;
	@Autowired private PlatformTransactionManager manager;
	@Autowired private JdbcTemplate jdbc;
	@PersistenceContext private EntityManager entityManager;
	@MockitoBean private Clock clock;
	@MockitoBean private AiClient ai;
	private Instant now;
	private User child;
	private String caseId;

	@DynamicPropertySource
	static void ownedSyntheticDatabase(DynamicPropertyRegistry registry) throws SQLException {
		String url = System.getenv("GUARDIAN_CONSENT_FENCE_MYSQL_URL");
		assertThat(url).matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_consent_fence_mysql_synthetic(?:\\?.*)?$");
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
	void committedSyntheticApproval() {
		now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		when(clock.instant()).thenReturn(now);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(DATABASE);
		assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
		assertThat(jdbc.queryForObject("select @@datadir", String.class).replace('\\', '/').replaceAll("/+$", "")).isEqualTo(DATADIR);
		assertThat(policy.ready()).isTrue();
		jdbc.execute("create table if not exists guardian_fence_mysql_outputs (output_id varchar(36) primary key, case_id varchar(36) not null, payload varchar(100) not null) engine=InnoDB");
		caseId = UUID.randomUUID().toString();
		child = transaction().execute(status -> {
			User account = User.create("synthetic-fence-" + caseId + "@example.test", "!synthetic", "Synthetic child");
			account.recordSignupDateOfBirth(LocalDate.of(2013, 1, 1));
			account.verifyEmail(now.minusSeconds(60));
			approve(account);
			return users.saveAndFlush(account);
		});
	}

	@ParameterizedTest(name = "stale {0} context / {1}")
	@CsvSource({"FENCE_LOOKUP_ONLY,REVOKE", "FENCE_LOOKUP_ONLY,REAPPROVE",
		"PESSIMISTIC_READ,REVOKE", "PESSIMISTIC_READ,REAPPROVE",
		"PESSIMISTIC_WRITE,REVOKE", "PESSIMISTIC_WRITE,REAPPROVE",
		"PROXY_READ,REVOKE", "PROXY_READ,REAPPROVE",
		"PROXY_WRITE,REVOKE", "PROXY_WRITE,REAPPROVE"})
	void existingRepeatableReadSnapshotCannotSaveAfterCommittedConsentChange(LockContext lockContext, Change change) throws Exception {
		GuardianConsentFence.Snapshot original = fence.capture(child.getId());
		assertThat(fence.complete(original, this::storeOutput)).isEqualTo(1);
		AtomicBoolean lateWriter = new AtomicBoolean();
		AtomicLong freshEpoch = new AtomicLong(-1);
		AtomicLong epochAfterRefresh = new AtomicLong(-1);
		AtomicReference<LockModeType> lockAfterRefresh = new AtomicReference<>();
		Throwable failure;
		try (var executor = Executors.newSingleThreadExecutor()) {
			failure = catchThrowable(() -> transaction().executeWithoutResult(status -> {
				assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
				User stale = lockContext.proxy()
					? users.getReferenceById(child.getId()) : users.findById(child.getId()).orElseThrow();
				if (lockContext.proxy()) {
					assertThat(stale).isInstanceOf(HibernateProxy.class);
					assertThat(HibernateProxy.extractLazyInitializer(stale).isUninitialized()).isTrue();
				}
				assertThat(stale.getGuardianConsentEpoch()).isEqualTo(original.guardianConsentEpoch());
				User implementation = loadedUser(stale);
				assertThat(implementation).isNotNull();
				assertThat(entityManager.contains(stale)).isTrue();
				assertThat(entityManager.contains(implementation)).isTrue();
				try {
					freshEpoch.set(executor.submit(() -> changeApproval(change)).get(5, TimeUnit.SECONDS));
				} catch (Exception changed) { throw new IllegalStateException(changed); }
				assertThat(freshEpoch.get()).isGreaterThan(original.guardianConsentEpoch());
				assertThat(stale.getGuardianConsentEpoch()).isEqualTo(original.guardianConsentEpoch());
				assertThat(stale.isGuardianAiConsentAllowed()).isTrue();
				if (lockContext == LockContext.PESSIMISTIC_READ || lockContext == LockContext.PROXY_READ) {
					assertThat(users.findByIdForBusinessAccess(child.getId()).orElseThrow()).isSameAs(stale);
				} else if (lockContext.write()) {
					assertThat(users.findByIdForUpdate(child.getId()).orElseThrow()).isSameAs(stale);
				}
				// A current locking lookup can retain the already-managed entity's older state.
				assertThat(stale.getGuardianConsentEpoch()).isEqualTo(original.guardianConsentEpoch());
				if (lockContext.proxy()) {
					assertThat(implementation).isNotSameAs(stale);
					assertThat(heldLock(stale)).isEqualTo(lockContext.write() ? LockMode.PESSIMISTIC_WRITE : LockMode.PESSIMISTIC_READ);
					System.out.println("SYNTHETIC_PROXY_FENCE stage=beforeStaleComplete context=" + lockContext
						+ " change=" + change + " " + proxyState(stale));
				}
				try {
					fence.complete(original, () -> { lateWriter.set(true); return storeOutput(); });
				} finally {
					epochAfterRefresh.set(stale.getGuardianConsentEpoch());
					lockAfterRefresh.set(entityManager.getLockMode(stale));
					assertThat(loadedUser(stale)).isSameAs(implementation);
					assertThat(entityManager.contains(stale)).isTrue();
					if (lockContext.proxy()) {
						System.out.println("SYNTHETIC_PROXY_FENCE stage=afterStaleComplete context=" + lockContext
							+ " change=" + change + " callback=" + lateWriter.get() + " " + proxyState(stale));
					}
				}
			}));
		}
		System.out.println("SYNTHETIC_CONSENT_RR context=" + lockContext + " change=" + change
			+ " originalEpoch=" + original.guardianConsentEpoch() + " committedEpoch=" + freshEpoch.get()
			+ " refreshedEpoch=" + epochAfterRefresh.get() + " lock=" + lockAfterRefresh.get()
			+ " lateWriter=" + lateWriter.get() + " outputCount=" + outputCount()
			+ " failure=" + (failure instanceof BusinessException business ? business.errorCode().code() : failure));
		assertThat(failure).isInstanceOfSatisfying(BusinessException.class,
			business -> assertThat(business.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		assertThat(epochAfterRefresh.get()).isEqualTo(freshEpoch.get());
		assertThat(lockAfterRefresh.get()).isEqualTo(lockContext.write()
			? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ);
		assertThat(lateWriter).isFalse();
		assertThat(outputCount()).isEqualTo(1);
		verifyNoInteractions(ai);
	}

	@ParameterizedTest(name = "valid output preserves existing write lock / flushed profile {0} / proxy {1}")
	@CsvSource({"false,false", "true,false", "false,true", "true,true"})
	void existingWriteLockRemainsHeldUntilValidOutputCommitsAndLaterRevocationCannotRemoveHistory(boolean flushedProfile, boolean proxy) throws Exception {
		GuardianConsentFence.Snapshot original = fence.capture(child.getId());
		CountDownLatch attempted = new CountDownLatch(1);
		AtomicReference<Future<Long>> revoked = new AtomicReference<>();
		AtomicBoolean validWriter = new AtomicBoolean();
		LockMode expectedLock = flushedProfile ? LockMode.WRITE : LockMode.PESSIMISTIC_WRITE;
		long revokedEpoch;
		try (var executor = Executors.newSingleThreadExecutor()) {
			Integer saved = transaction().execute(status -> {
				User reference = proxy ? users.getReferenceById(child.getId()) : null;
				if (proxy) {
					assertThat(reference).isInstanceOf(HibernateProxy.class);
					assertThat(HibernateProxy.extractLazyInitializer(reference).isUninitialized()).isTrue();
				}
				User locked = users.findByIdForUpdate(child.getId()).orElseThrow();
				if (proxy) assertThat(locked).isSameAs(reference);
				User implementation = loadedUser(locked);
				assertThat(implementation).isNotNull();
				if (flushedProfile) {
					locked.updateProfile("Synthetic updated profile", null);
					users.flush();
				}
				assertThat(heldLock(locked)).isEqualTo(expectedLock);
				if (proxy) {
					assertThat(implementation).isNotSameAs(locked);
					System.out.println("SYNTHETIC_PROXY_FENCE stage=beforeValidComplete flushedProfile=" + flushedProfile
						+ " " + proxyState(locked));
				}
				Integer count;
				try {
					count = fence.complete(original, () -> {
						validWriter.set(true);
						assertThat(heldLock(locked)).isEqualTo(expectedLock);
						assertThat(locked.getName()).isEqualTo(flushedProfile ? "Synthetic updated profile" : "Synthetic child");
						revoked.set(executor.submit(() -> { attempted.countDown(); return changeApproval(Change.REVOKE); }));
						try {
							assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
							awaitUserLockWait();
							assertThatThrownBy(() -> revoked.get().get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
						} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
						return storeOutput();
					});
				} finally {
					assertThat(loadedUser(locked)).isSameAs(implementation);
					assertThat(entityManager.contains(locked)).isTrue();
					if (proxy) {
						System.out.println("SYNTHETIC_PROXY_FENCE stage=afterValidComplete flushedProfile=" + flushedProfile
							+ " callback=" + validWriter.get() + " " + proxyState(locked));
					}
				}
				assertThat(heldLock(locked)).isEqualTo(expectedLock);
				return count;
			});
			assertThat(saved).isEqualTo(1);
			assertThat(validWriter).isTrue();
			revokedEpoch = revoked.get().get(10, TimeUnit.SECONDS);
			assertThat(revokedEpoch).isGreaterThan(original.guardianConsentEpoch());
		}
		assertThatThrownBy(() -> fence.complete(original, this::storeOutput)).isInstanceOfSatisfying(BusinessException.class,
			business -> assertThat(business.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		assertThat(outputCount()).isEqualTo(1);
		String storedName = transaction().execute(status -> users.findById(child.getId()).orElseThrow().getName());
		assertThat(storedName).isEqualTo(flushedProfile ? "Synthetic updated profile" : "Synthetic child");
		System.out.println("SYNTHETIC_CONSENT_WRITE profileFlushed=" + flushedProfile + " proxy=" + proxy + " preservedMode=" + expectedLock
			+ " originalEpoch=" + original.guardianConsentEpoch() + " revokedEpoch=" + revokedEpoch
			+ " outputCount=" + outputCount() + " profilePreserved=true");
		verifyNoInteractions(ai);
	}

	private LockMode heldLock(User account) {
		return entityManager.unwrap(SessionImplementor.class).getPersistenceContextInternal().getEntry(loadedUser(account)).getLockMode();
	}

	/** Inspect only the implementation already loaded by Hibernate; do not initialize or reconnect a proxy. */
	private User loadedUser(User account) {
		var initializer = HibernateProxy.extractLazyInitializer(account);
		if (initializer == null) return account;
		var session = entityManager.unwrap(SessionImplementor.class);
		assertThat(initializer.getSession()).isSameAs(session);
		return (User)initializer.getImplementation(session);
	}

	private String proxyState(User account) {
		var session = entityManager.unwrap(SessionImplementor.class);
		var context = session.getPersistenceContextInternal();
		var initializer = HibernateProxy.extractLazyInitializer(account);
		User implementation = loadedUser(account);
		var raw = context.getEntry(account);
		var managed = implementation == null ? null : context.getEntry(implementation);
		return "wrapperClass=" + account.getClass().getSimpleName() + " proxy=" + (initializer != null)
			+ " sameSession=" + (initializer == null || initializer.getSession() == session)
			+ " wrapperManaged=" + entityManager.contains(account)
			+ " implementationManaged=" + (implementation != null && entityManager.contains(implementation))
			+ " wrapperIdentity=" + System.identityHashCode(account)
			+ " implementationIdentity=" + (implementation == null ? "absent" : System.identityHashCode(implementation))
			+ " rawEntryMode=" + (raw == null ? "absent" : raw.getLockMode())
			+ " implementationMode=" + (managed == null ? "absent" : managed.getLockMode())
			+ " epoch=" + (implementation == null ? "absent" : implementation.getGuardianConsentEpoch());
	}

	private long changeApproval(Change change) {
		return transaction().execute(status -> {
			User account = users.findByIdForUpdate(child.getId()).orElseThrow();
			account.clearGuardianTeamApproval(false);
			if (change == Change.REAPPROVE) approve(account);
			users.flush();
			if (change == Change.REAPPROVE) {
				assertThat(UserBusinessAccessState.from(account).aiEligibilityFailure(
					Clock.fixed(now, ZoneOffset.UTC), policy.ready(), policy.configurationDigest())).isNull();
			}
			return account.getGuardianConsentEpoch();
		});
	}

	private void approve(User user) {
		user.recordGuardianTeamApproval(now.plusSeconds(3600), true);
		user.recordGuardianTeamPolicyDigest(policy.configurationDigest());
	}

	private TransactionTemplate transaction() {
		TransactionTemplate transaction = new TransactionTemplate(manager);
		transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return transaction;
	}

	private int storeOutput() {
		return jdbc.update("insert into guardian_fence_mysql_outputs(output_id,case_id,payload) values(?,?,?)",
			UUID.randomUUID().toString(), caseId, "Synthetic AI output saved under valid consent");
	}

	private int outputCount() {
		return jdbc.queryForObject("select count(*) from guardian_fence_mysql_outputs where case_id = ?", Integer.class, caseId);
	}

	private void awaitUserLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Integer waits = jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w "
				+ "join performance_schema.data_locks l on l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID "
				+ "where l.OBJECT_SCHEMA=? and l.OBJECT_NAME='users' and l.INDEX_NAME='PRIMARY'", Integer.class, DATABASE);
			if (waits != null && waits > 0) return;
			Thread.sleep(20);
		}
		throw new AssertionError("Synthetic revocation did not wait for the account's database lock");
	}

	private enum LockContext {
		FENCE_LOOKUP_ONLY, PESSIMISTIC_READ, PESSIMISTIC_WRITE, PROXY_READ, PROXY_WRITE;
		boolean proxy() { return this == PROXY_READ || this == PROXY_WRITE; }
		boolean write() { return this == PESSIMISTIC_WRITE || this == PROXY_WRITE; }
	}
	private enum Change { REVOKE, REAPPROVE }
}
