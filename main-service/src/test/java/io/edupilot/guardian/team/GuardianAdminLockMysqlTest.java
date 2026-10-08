package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.hibernate.LockMode;
import org.hibernate.engine.spi.SessionImplementor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.admin.AdminUserService;
import io.edupilot.ai.AiClient;
import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** Real services, canonical MySQL indexes and disposable synthetic accounts only. */
@EnabledIfEnvironmentVariable(named = "GUARDIAN_ADMIN_LOCK_MYSQL_URL",
	matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_admin_lock_jpa_synthetic(?:\\?.*)?$")
@SpringBootTest(properties = {
	"spring.datasource.username=root", "spring.datasource.password=",
	"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "spring.flyway.enabled=true",
	"spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false",
	"logging.level.org.hibernate.SQL=DEBUG",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-admin-lock-mysql", "edupilot.mail.enabled=false",
	"edupilot.guardian.team.recovery-initial-delay-ms=3600000",
	"edupilot.guardian.team.enabled=true", "edupilot.guardian.team.policy-confirmed=true",
	"edupilot.guardian.team.portal-base-url=https://guardian.example.invalid",
	"edupilot.guardian.team.notice-version=synthetic-lock-v1",
	"edupilot.guardian.team.notice-digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	"edupilot.guardian.team.notice-url=https://guardian.example.invalid/notice",
	"edupilot.guardian.team.collection-items-text=합성 시험 확인 결과",
	"edupilot.guardian.team.purposes-text=합성 시험의 보호자 확인",
	"edupilot.guardian.team.retention-text=합성 시험에서 확인된 보유 안내",
	"edupilot.guardian.team.refusal-text=동의를 거부하면 보호자 확인이 필요한 서비스를 이용할 수 없습니다.",
	"edupilot.guardian.team.reply-contact=reply@example.invalid", "edupilot.guardian.team.reply-channel=EMAIL_REPLY",
	"edupilot.guardian.team.reviewer-ids=2", "edupilot.guardian.team.required-scopes=SERVICE",
	"edupilot.guardian.team.optional-ai-scope=EXTERNAL_AI", "edupilot.guardian.team.optional-consent-text=합성 데이터의 외부 AI 전송에 대한 선택 동의",
	"edupilot.guardian.team.link-ttl=30m", "edupilot.guardian.team.request-ttl=5d",
	"edupilot.guardian.team.approved-evidence-retention=30d", "edupilot.guardian.team.approval-validity=7d"
})
@ActiveProfiles("jpa-context")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GuardianAdminLockMysqlTest {
	private static final String DATABASE = "guardian_admin_lock_jpa_synthetic";
	private static final ThreadLocal<Boolean> TEAM_CALL = new ThreadLocal<>();
	@Autowired GuardianTeamService team;
	@Autowired AdminUserService admin;
	@Autowired GuardianTeamProperties policy;
	@Autowired GuardianTeamRequestRepository requests;
	@Autowired PlatformTransactionManager manager;
	@Autowired JdbcTemplate jdbc;
	@PersistenceContext EntityManager entityManager;
	@MockitoSpyBean UserRepository users;
	@MockitoBean Clock clock;
	@MockitoBean PasswordEncoder passwords;
	@MockitoBean RefreshTokenService refreshTokens;
	@MockitoBean GuardianTeamMailCleanup mailCleanup;
	@MockitoBean AiClient ai;
	private Instant now;
	private User child;
	private User reviewer;
	private User operator;
	private GuardianTeamDtos.Status pending;

	@DynamicPropertySource
	static void ownedSyntheticDatabase(DynamicPropertyRegistry registry) throws SQLException {
		String url = System.getenv("GUARDIAN_ADMIN_LOCK_MYSQL_URL");
		String serverUrl = "jdbc:mysql://127.0.0.1:33316/";
		try (var connection = DriverManager.getConnection(serverUrl, "root", ""); var sql = connection.createStatement()) {
			try (var identity = sql.executeQuery("select @@port,@@datadir")) {
				assertThat(identity.next()).isTrue(); assertThat(identity.getInt(1)).isEqualTo(33316);
				assertThat(identity.getString(2).replace('\\', '/').replaceAll("/+$", ""))
					.isEqualTo("C:/Users/russe/Documents/Codex/2026-10-03/task-2/mysql-isolated");
			}
			sql.execute("create database if not exists " + DATABASE);
		}
		registry.add("spring.datasource.url", () -> url);
	}

	@BeforeEach
	void committedSyntheticFixture() {
		now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		when(clock.instant()).thenReturn(now); when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(DATABASE);
		assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
		jdbc.update("delete from guardian_team_operations"); jdbc.update("delete from guardian_team_events");
		jdbc.update("delete from guardian_team_requests"); jdbc.update("delete from users");
		jdbc.execute("alter table users auto_increment=1");
		child = save(UserRole.LEARNER, LocalDate.of(2015, 1, 1));
		reviewer = save(UserRole.ADMIN, LocalDate.of(1980, 1, 1));
		operator = save(UserRole.ADMIN, LocalDate.of(1980, 1, 1));
		assertThat(child.getId()).isEqualTo(1); assertThat(reviewer.getId()).isEqualTo(2);
		assertThat(operator.getId()).isEqualTo(3); assertThat(policy.ready()).isTrue();
		pending = team.intake(child.getId(), new GuardianTeamDtos.Intake("synthetic-intake", null, null, false)).status();
		// Selective canonical status index makes the original admin-first query plan observable.
		jdbc.execute("set session cte_max_recursion_depth=2100");
		jdbc.update("insert into users(id,email,password_hash,name,role,status) "
			+ "with recursive sequence(n) as (select 1 union all select n+1 from sequence where n<2000) "
			+ "select n+1000,concat('lock-background-',n,'@example.invalid'),'synthetic','합성 배경','LEARNER','SUSPENDED' from sequence");
		jdbc.execute("analyze table users");
	}

	@ParameterizedTest(name = "{0} owner / {1} admin mutation")
	@CsvSource({"ACTIVE,SUSPEND", "ACTIVE,CHANGE_ROLE", "SUSPENDED,SUSPEND", "SUSPENDED,CHANGE_ROLE"})
	void adminMutationAndTeamReviewUseOneUserLockOrder(UserStatus status, Mutation mutation) throws Exception {
		if (status == UserStatus.SUSPENDED) {
			transaction().executeWithoutResult(tx -> users.findByIdForUpdate(child.getId()).orElseThrow()
				.suspend("합성 시험 정지", operator.getId(), now));
		}
		CountDownLatch childLocked = new CountDownLatch(1);
		CountDownLatch releaseTeam = new CountDownLatch(1);
		var repositoryAnswer = mockingDetails(users).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			Object result = repositoryAnswer.answer(invocation);
			if (Boolean.TRUE.equals(TEAM_CALL.get()) && invocation.getArgument(0).equals(child.getId())) {
				assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
				childLocked.countDown();
				if (!releaseTeam.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("합성 TEAM barrier 시간 초과"); }
			}
			return result;
		}).when(users).findByIdForUpdate(anyLong());
		try (var pool = Executors.newFixedThreadPool(2)) {
			var review = pool.submit(() -> {
				TEAM_CALL.set(true);
				try { return outcome(() -> team.detail(reviewer.getId(), pending.requestId())); }
				finally { TEAM_CALL.remove(); }
			});
			assertThat(childLocked.await(5, TimeUnit.SECONDS)).isTrue();
			var mutationCall = pool.submit(() -> outcome(() -> {
				if (mutation == Mutation.SUSPEND) { admin.suspend(operator.getId(), child.getId(), "합성 시험 정지"); }
				else { admin.changeRole(operator.getId(), child.getId(), UserRole.INSTRUCTOR); }
			}));
			awaitUserLockWait(); // Real server confirms the admin request is waiting while TEAM holds the child.
			releaseTeam.countDown();
			Outcome reviewed = review.get(15, TimeUnit.SECONDS);
			Outcome changed = mutationCall.get(15, TimeUnit.SECONDS);
			System.out.println("SYNTHETIC_ADMIN_TEAM_CASE status=" + status + " mutation=" + mutation
				+ " team=" + reviewed + " admin=" + changed);
			assertThat(reviewed.sqlError()).isNull(); assertThat(changed.sqlError()).isNull();
			assertThat(reviewed.result()).isEqualTo("OK");
			assertThat(changed.result()).isEqualTo(status == UserStatus.SUSPENDED && mutation == Mutation.SUSPEND
				? ErrorCode.USER_INACTIVE.code() : "OK");
		} finally { releaseTeam.countDown(); }
		assertThat(jdbc.queryForObject("select count(*) from users where role='ADMIN' and status='ACTIVE'", Integer.class)).isEqualTo(2);
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianApprovedUntil()).isNull();
	}

	@Test
	void suspendedApplicantRemainsIneligibleForApproval() {
		var confirmed = team.confirm(reviewer.getId(), pending.requestId(), new GuardianTeamDtos.Confirmation("synthetic-confirm",
			pending.generation(), pending.revision(), GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY, "synthetic-reference",
			now, pending.noticeVersion(), pending.noticeDigest(), GuardianTeamRequest.Relationship.PARENT, Set.of("SERVICE"),
			true, true, true, true, true));
		transaction().executeWithoutResult(tx -> users.findByIdForUpdate(child.getId()).orElseThrow()
			.suspend("합성 시험 정지", operator.getId(), now));
		assertThatThrownBy(() -> team.decide(reviewer.getId(), pending.requestId(), new GuardianTeamDtos.Decision("synthetic-approve",
			confirmed.generation(), confirmed.revision(), GuardianTeamDtos.DecisionKind.APPROVE, null, true, true, true, true)))
			.isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE));
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianApprovedUntil()).isNull();
	}

	@ParameterizedTest(name = "stale {0} actor / {1} / already write locked {2}")
	@CsvSource({"DEMOTED,SUSPEND,false", "DEMOTED,CHANGE_ROLE,false", "SUSPENDED,SUSPEND,false", "SUSPENDED,CHANGE_ROLE,false",
		"DEMOTED,SUSPEND,true", "DEMOTED,CHANGE_ROLE,true", "SUSPENDED,SUSPEND,true", "SUSPENDED,CHANGE_ROLE,true"})
	void adminMutationRefreshesAuthorityEvenInsideAnExistingRepeatableReadTransaction(
		ActorRevocation revocation, Mutation mutation, boolean alreadyWriteLocked) {
		try (var pool = Executors.newSingleThreadExecutor()) {
			assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> {
				assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
				User stale = users.findById(reviewer.getId()).orElseThrow();
				try {
					pool.submit(() -> {
						if (revocation == ActorRevocation.DEMOTED) {
							admin.changeRole(operator.getId(), reviewer.getId(), UserRole.LEARNER);
						} else { admin.suspend(operator.getId(), reviewer.getId(), "합성 담당자 권한 회수"); }
					}).get(5, TimeUnit.SECONDS);
				} catch (Exception error) { throw new IllegalStateException(error); }
				assertThat(stale.getRole()).isEqualTo(UserRole.ADMIN); assertThat(stale.isActive()).isTrue();
				if (alreadyWriteLocked) {
					assertThat(users.findByIdForUpdate(reviewer.getId()).orElseThrow()).isSameAs(stale);
					assertThat(entityManager.getLockMode(stale)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
				}
				if (mutation == Mutation.SUSPEND) { admin.suspend(reviewer.getId(), child.getId(), "권한 회수 후 합성 요청"); }
				else { admin.changeRole(reviewer.getId(), child.getId(), UserRole.INSTRUCTOR); }
			})).isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
		}
		User unchanged = users.findById(child.getId()).orElseThrow();
		assertThat(unchanged.isActive()).isTrue(); assertThat(unchanged.getRole()).isEqualTo(UserRole.LEARNER);
	}

	@ParameterizedTest(name = "current target guardian grant / already write locked {0}")
	@ValueSource(booleans = {false, true})
	void adminMutationHydratesTheCurrentGuardianGrantWithoutDroppingManagedIdentityOrPendingProfileChanges(boolean alreadyWriteLocked) {
		try (var pool = Executors.newSingleThreadExecutor()) {
			transaction().executeWithoutResult(tx -> {
				User stale = users.findById(child.getId()).orElseThrow();
				long previousEpoch = stale.getGuardianConsentEpoch();
				GuardianTeamDtos.Status approved;
				try {
					approved = pool.submit(() -> {
						var confirmed = team.confirm(reviewer.getId(), pending.requestId(), new GuardianTeamDtos.Confirmation("synthetic-current-confirm",
							pending.generation(), pending.revision(), GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY, "synthetic-current-reference",
							now, pending.noticeVersion(), pending.noticeDigest(), GuardianTeamRequest.Relationship.PARENT, Set.of("SERVICE", "EXTERNAL_AI"),
							true, true, true, true, true));
						return team.decide(reviewer.getId(), pending.requestId(), new GuardianTeamDtos.Decision("synthetic-current-approve",
							confirmed.generation(), confirmed.revision(), GuardianTeamDtos.DecisionKind.APPROVE, null, true, true, true, true));
					}).get(5, TimeUnit.SECONDS);
				} catch (Exception error) { throw new IllegalStateException(error); }
				assertThat(stale.getGuardianApprovedUntil()).isNull(); assertThat(stale.getGuardianConsentEpoch()).isEqualTo(previousEpoch);
				if (alreadyWriteLocked) {
					assertThat(users.findByIdForUpdate(child.getId()).orElseThrow()).isSameAs(stale);
					assertThat(entityManager.getLockMode(stale)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
				}
				stale.updateProfile("합성 프로필 수정 보존", "합성 기관");
				admin.changeRole(operator.getId(), child.getId(), UserRole.INSTRUCTOR);
				assertThat(users.findById(child.getId()).orElseThrow()).isSameAs(stale);
				// The locking query flushed the pending profile; refresh must retain its stronger WRITE bookkeeping.
				assertThat(entityManager.unwrap(SessionImplementor.class).getPersistenceContextInternal().getEntry(stale).getLockMode())
					.isEqualTo(LockMode.WRITE);
				assertThat(stale.getAgeVerificationState()).isEqualTo(AgeVerificationState.TEAM_APPROVED);
				assertThat(stale.getGuardianApprovedUntil()).isEqualTo(approved.approvedUntil());
				assertThat(stale.getGuardianConsentEpoch()).isEqualTo(previousEpoch + 1);
				assertThat(stale.isGuardianAiConsentAllowed()).isTrue();
				assertThat(stale.getGuardianApprovalPolicyDigest()).isEqualTo(policy.configurationDigest());
				assertThat(stale.getName()).isEqualTo("합성 프로필 수정 보존");
			});
		}
		User current = users.findById(child.getId()).orElseThrow();
		assertThat(current.getRole()).isEqualTo(UserRole.INSTRUCTOR);
		assertThat(current.getAgeVerificationState()).isEqualTo(AgeVerificationState.TEAM_APPROVED);
		assertThat(current.getGuardianApprovedUntil()).isEqualTo(now.plus(java.time.Duration.ofDays(7)));
		assertThat(current.isGuardianAiConsentAllowed()).isTrue();
		assertThat(current.getGuardianApprovalPolicyDigest()).isEqualTo(policy.configurationDigest());
		assertThat(current.getName()).isEqualTo("합성 프로필 수정 보존"); assertThat(current.getAffiliation()).isEqualTo("합성 기관");
	}

	@ParameterizedTest(name = "current target suspension / already write locked {0}")
	@ValueSource(booleans = {false, true})
	void adminMutationRejectsTheCurrentSuspendedTargetInsideAnExistingRepeatableReadTransaction(boolean alreadyWriteLocked) {
		try (var pool = Executors.newSingleThreadExecutor()) {
			assertThatThrownBy(() -> transaction().executeWithoutResult(tx -> {
				User stale = users.findById(child.getId()).orElseThrow();
				try { pool.submit(() -> admin.suspend(operator.getId(), child.getId(), "합성 대상 상태 변경")).get(5, TimeUnit.SECONDS); }
				catch (Exception error) { throw new IllegalStateException(error); }
				assertThat(stale.isActive()).isTrue();
				if (alreadyWriteLocked) {
					assertThat(users.findByIdForUpdate(child.getId()).orElseThrow()).isSameAs(stale);
					assertThat(entityManager.getLockMode(stale)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
				}
				admin.suspend(operator.getId(), child.getId(), "이전 스냅샷에서 반복한 합성 요청");
			})).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE));
		}
		assertThat(users.findById(child.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.SUSPENDED);
	}

	@ParameterizedTest(name = "TEAM uses its fresh transaction after {0} reviewer revocation")
	@ValueSource(strings = {"DEMOTED", "SUSPENDED"})
	void teamReviewDoesNotInheritAnOuterRepeatableReadReviewersSnapshot(String revocationName) {
		ActorRevocation revocation = ActorRevocation.valueOf(revocationName);
		try (var pool = Executors.newSingleThreadExecutor()) {
			transaction().executeWithoutResult(tx -> {
				User stale = users.findById(reviewer.getId()).orElseThrow();
				try {
					pool.submit(() -> {
						if (revocation == ActorRevocation.DEMOTED) { admin.changeRole(operator.getId(), reviewer.getId(), UserRole.LEARNER); }
						else { admin.suspend(operator.getId(), reviewer.getId(), "합성 TEAM 담당 권한 회수"); }
					}).get(5, TimeUnit.SECONDS);
				} catch (Exception error) { throw new IllegalStateException(error); }
				assertThat(stale.getRole()).isEqualTo(UserRole.ADMIN); assertThat(stale.isActive()).isTrue();
				assertThatThrownBy(() -> team.detail(reviewer.getId(), pending.requestId()))
					.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
			});
		}
	}

	@Test
	void simultaneousAdminDemotionsPreserveOneActiveAdminAndDenyTheRevokedActor() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> { start.await(); return outcome(() -> admin.changeRole(operator.getId(), reviewer.getId(), UserRole.LEARNER)); });
			var b = pool.submit(() -> { start.await(); return outcome(() -> admin.changeRole(reviewer.getId(), operator.getId(), UserRole.LEARNER)); });
			start.countDown();
			Outcome first = a.get(10, TimeUnit.SECONDS), second = b.get(10, TimeUnit.SECONDS);
			assertThat(first.sqlError()).isNull(); assertThat(second.sqlError()).isNull();
			assertThat(java.util.List.of(first.result(), second.result())).containsExactlyInAnyOrder("OK", "ACCESS_DENIED");
		}
		assertThat(jdbc.queryForObject("select count(*) from users where role='ADMIN' and status='ACTIVE'", Integer.class)).isEqualTo(1);
		User remaining = users.findAll().stream().filter(account -> account.getRole() == UserRole.ADMIN && account.isActive()).findFirst().orElseThrow();
		assertThatThrownBy(() -> admin.suspend(remaining.getId(), remaining.getId(), "합성 자기 정지"))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ADMIN_SELF_MODIFICATION));
		assertThatThrownBy(() -> admin.changeRole(remaining.getId(), remaining.getId(), UserRole.LEARNER))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ADMIN_SELF_MODIFICATION));
	}

	@ParameterizedTest(name = "two exact User locks / isolation {0}")
	@ValueSource(ints = {TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_READ_COMMITTED})
	void exactActorAndTargetLocksDoNotScanOrWaitOnAnAlreadyLockedUnrelatedUser(int isolation) throws Exception {
		CountDownLatch pairReady = new CountDownLatch(1), releasePair = new CountDownLatch(1);
		try (var busyConnection = DriverManager.getConnection(System.getenv("GUARDIAN_ADMIN_LOCK_MYSQL_URL"), "root", "");
			var sql = busyConnection.createStatement(); var pool = Executors.newSingleThreadExecutor()) {
			busyConnection.setAutoCommit(false);
			try (var busy = sql.executeQuery("select id from users where id=1500 for update")) {
				assertThat(busy.next()).isTrue(); assertThat(busy.getLong(1)).isEqualTo(1500);
			}
			try {
				var call = pool.submit(() -> {
					var tx = transaction(); tx.setIsolationLevel(isolation);
					tx.executeWithoutResult(status -> {
						long scanBefore = jdbc.queryForObject("select variable_value from performance_schema.session_status where variable_name='Handler_read_next'", Long.class);
						admin.changeRole(operator.getId(), child.getId(), UserRole.LEARNER); // Existing role; hold the real service locks until inspection.
						long scanAfter = jdbc.queryForObject("select variable_value from performance_schema.session_status where variable_name='Handler_read_next'", Long.class);
						var plan = jdbc.queryForMap("explain select account.* from users account where account.id=? for update", child.getId());
						assertThat(plan.get("key")).isEqualTo("PRIMARY"); assertThat(((Number)plan.get("rows")).longValue()).isEqualTo(1);
						var held = jdbc.queryForList("select lock_data from performance_schema.data_locks where object_schema=? "
							+ "and object_name='users' and lock_type='RECORD' and thread_id="
							+ "(select thread_id from performance_schema.threads where processlist_id=connection_id()) order by lock_data", String.class, DATABASE);
						assertThat(held).containsExactly(child.getId().toString(), operator.getId().toString());
						assertThat(scanAfter - scanBefore).isZero();
						System.out.println("SYNTHETIC_ADMIN_PAIR_SCOPE isolation=" + jdbc.queryForObject("select @@transaction_isolation", String.class)
							+ " locks=" + held + " primaryNextReads=" + (scanAfter - scanBefore) + " plan=" + plan);
						pairReady.countDown();
						try { if (!releasePair.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("합성 pair 검사 시간 초과"); } }
						catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
					});
				});
				assertThat(pairReady.await(5, TimeUnit.SECONDS)).as("id1500을 잠근 동안 실제 변경 경로가 완료되고 두 행만 잠겨야 합니다.").isTrue();
				// Even the other ACTIVE ADMIN is not locked by this mutation.
				try (var otherAdmin = sql.executeQuery("select id from users where id=2 for update nowait")) {
					assertThat(otherAdmin.next()).isTrue(); assertThat(otherAdmin.getLong(1)).isEqualTo(reviewer.getId());
				}
				releasePair.countDown(); call.get(5, TimeUnit.SECONDS);
			} finally { releasePair.countDown(); busyConnection.rollback(); }
		}
	}

	private void awaitUserLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int waiting = jdbc.queryForObject("select count(*) from performance_schema.data_lock_waits w "
				+ "join performance_schema.data_locks r on r.engine_lock_id=w.requesting_engine_lock_id "
				+ "where r.object_schema=? and r.object_name='users'", Integer.class, DATABASE);
			if (waiting > 0) { return; }
			Thread.sleep(20);
		}
		throw new AssertionError("합성 관리자 요청의 실제 User 잠금 대기를 확인하지 못했습니다.");
	}

	private User save(UserRole role, LocalDate birth) {
		User user = User.create(UUID.randomUUID() + "@example.invalid", "synthetic", "합성 계정", role);
		user.recordSignupDateOfBirth(birth); user.verifyEmail(now); return users.saveAndFlush(user);
	}
	private TransactionTemplate transaction() { return new TransactionTemplate(manager); }
	private static Outcome outcome(Runnable action) {
		try { action.run(); return new Outcome("OK", null, null); }
		catch (BusinessException error) { return new Outcome(error.errorCode().code(), null, null); }
		catch (Exception error) {
			Throwable cause = error;
			while (cause != null) {
				if (cause instanceof SQLException sql) { return new Outcome(error.getClass().getSimpleName(), sql.getErrorCode(), sql.getSQLState()); }
				cause = cause.getCause();
			}
			throw error;
		}
	}
	private record Outcome(String result, Integer sqlError, String sqlState) { }
	private enum Mutation { SUSPEND, CHANGE_ROLE }
	private enum ActorRevocation { DEMOTED, SUSPENDED }
}
