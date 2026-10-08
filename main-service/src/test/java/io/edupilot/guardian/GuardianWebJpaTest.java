package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.*;
import io.edupilot.global.error.*;
import io.edupilot.mail.EmailService;
import io.edupilot.user.*;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-web;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-web", "edupilot.guardian.web.enabled=true",
	"edupilot.guardian.web.portal-base-url=https://guardian.example.invalid/",
	"edupilot.guardian.web.notice-version=synthetic-v1",
	"edupilot.guardian.web.notice-digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	"edupilot.guardian.web.notice-url=https://guardian.example.invalid/notice",
	"edupilot.guardian.web.max-code-attempts=3", "edupilot.guardian.web.recovery-initial-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
class GuardianWebJpaTest {
	private Instant baseline;
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("GUARDIAN_WEB_MYSQL"))) { return; }
		registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:33316/guardian_web_synthetic");
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private GuardianWebService service;
	@Autowired private GuardianWebPersistence persistence;
	@Autowired private GuardianWebProperties policy;
	@MockitoSpyBean private GuardianWebSecrets secrets;
	@Autowired private GuardianWebRequestRepository requests;
	@Autowired private GuardianVerificationRequestRepository oldRequests;
	@Autowired private UserRepository users;
	@Autowired private UserService userService;
	@Autowired private AgeEligibilityGate age;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private AuthSessionRepository sessions;
	@Autowired private io.edupilot.deletion.DeletionJournalLockRepository deletionLocks;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private WebApplicationContext context;
	@MockitoBean private Clock clock;
	@MockitoBean private GuardianPhoneProvider provider;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailService mail;
	@MockitoBean private EmailVerificationService emailVerification;
	@MockitoBean private GoogleIdTokenVerifier google;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		baseline = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		if ("true".equals(System.getenv("GUARDIAN_WEB_MYSQL"))) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("guardian_web_synthetic");
		}
		when(clock.instant()).thenReturn(baseline);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(provider.connected()).thenReturn(true);
		when(provider.send(anyString(), anyString(), any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(call.<Instant>getArgument(2)).isEqualTo(baseline.plusSeconds(300));
			return new GuardianPhoneProvider.Receipt("synthetic-ref");
		});
		when(provider.verify(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return GuardianPhoneProvider.Result.PHONE_CONFIRMED;
		});
		requests.deleteAll(); oldRequests.deleteAll(); refreshTokens.deleteAll(); sessions.deleteAll();
		// Accounts are unique per test; withdrawal leaves a deletion tombstone referencing its old account ID.
		if (!deletionLocks.existsById(1)) { deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial()); }
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void normalFlowRecordsVersionedConsentAndPhoneControlWithoutGrantingAgeEligibility() {
		User user = user(); String token = issue(user);
		assertThat(service.view(new GuardianWebDtos.Token(token), ip()).state()).isEqualTo(GuardianWebRequest.State.AWAITING_CONSENT);
		assertThat(service.view(new GuardianWebDtos.Token(token), ip()).consentRecorded()).isFalse();
		var pending = consent(token);
		assertThat(pending.state()).isEqualTo(GuardianWebRequest.State.PHONE_PENDING);
		var proof = service.verify(new GuardianWebDtos.Code(token, "123456"), ip());
		assertThat(proof.state()).isEqualTo(GuardianWebRequest.State.PHONE_CONFIRMED);
		assertThat(proof.consentRecorded()).isTrue(); assertThat(proof.phoneControlConfirmed()).isTrue();
		assertThat(proof.guardianRelationshipVerified()).isFalse();
		assertThat(proof.noticeVersion()).isEqualTo("synthetic-v1");
		var stored = row(token);
		assertThat(stored.tokenHash()).isEqualTo(GuardianWebSecrets.hash(token));
		assertThat(stored.providerReference()).isNull();
		assertThat(jdbc.queryForObject("select legal_guardian_declared from guardian_web_requests where id=?", Boolean.class, stored.id())).isTrue();
		assertThat(jdbc.queryForObject("select phone_fingerprint from guardian_web_requests where id=?", String.class, stored.id()))
			.matches("[a-f0-9]{64}").doesNotContain("+12025550123", token, "123456");
		assertThat(users.findById(user.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
		assertError(() -> age.requireEligible(user.getId()), ErrorCode.AGE_VERIFICATION_REQUIRED);
		verifyNoInteractions(ai, mail);
	}

	@Test
	void controllerRequiresLoginForIssuanceKeepsViewPublicAndDoesNotConsumeOnGet() throws Exception {
		mvc.perform(post("/api/auth/guardian-verification/link")).andExpect(status().isUnauthorized());
		User user = user(); String token = issue(user);
		mvc.perform(post("/api/auth/guardian-verification/view").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(jsonPath("$.data.state").value("AWAITING_CONSENT"))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(user.getEmail()))));
		mvc.perform(get("/api/auth/guardian-verification/consent")).andExpect(status().isMethodNotAllowed());
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.AWAITING_CONSENT);
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	void authenticatedLinkRemainsReachableBeforeEmailVerification() throws Exception {
		User user = users.saveAndFlush(User.create("synthetic-" + UUID.randomUUID() + "@example.com", "hash", "Synthetic", UserRole.LEARNER));
		mvc.perform(post("/api/auth/guardian-verification/link").header("Authorization", "Bearer " + tokens.createAccessToken(user)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("https://guardian.example.invalid/guardian-consent#token=")));
		assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
	}

	@Test
	void reissueInvalidatesOldLinkAndExpiryRejectsForgedOrLateToken() {
		User user = user(); String old = issue(user); String current = issue(user);
		assertError(() -> service.view(new GuardianWebDtos.Token(old), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
		assertError(() -> service.view(new GuardianWebDtos.Token("X".repeat(43)), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
		when(clock.instant()).thenReturn(baseline.plusSeconds(1801));
		assertError(() -> service.view(new GuardianWebDtos.Token(current), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	void staleNoticeOrUncheckedConsentNeverSendsSms() {
		String token = issue(user());
		assertError(() -> service.consent(new GuardianWebDtos.Consent(token, "old", true, true, "+12025550123"), ip()), ErrorCode.GUARDIAN_NOTICE_CHANGED);
		assertError(() -> service.consent(new GuardianWebDtos.Consent(token, "synthetic-v1", false, true, "+12025550123"), ip()), ErrorCode.VALIDATION_FAILED);
		assertError(() -> service.consent(new GuardianWebDtos.Consent(token, "synthetic-v1", true, false, "+12025550123"), ip()), ErrorCode.VALIDATION_FAILED);
		jdbc.update("update guardian_web_requests set notice_digest=? where token_hash=?", "b".repeat(64), GuardianWebSecrets.hash(token));
		assertThat(service.view(new GuardianWebDtos.Token(token), ip()).noticeUrl()).isNull();
		assertError(() -> consent(token), ErrorCode.GUARDIAN_NOTICE_CHANGED);
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.AWAITING_CONSENT);
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	void repeatedConsentOrVerificationIsRejectedAndSmsIsSentOnce() {
		String token = issue(user()); consent(token);
		assertError(() -> consent(token), ErrorCode.GUARDIAN_STATE_CONFLICT);
		service.verify(new GuardianWebDtos.Code(token, "123456"), ip());
		assertError(() -> service.verify(new GuardianWebDtos.Code(token, "123456"), ip()), ErrorCode.GUARDIAN_STATE_CONFLICT);
		verify(provider, times(1)).send(anyString(), anyString(), any());
		verify(provider, times(1)).verify(anyString(), anyString(), anyString(), any());
	}

	@Test
	void codeMismatchCapAndDisputeProduceExceptionIntakeWithoutApproval() {
		String token = issue(user()); consent(token);
		when(provider.verify(anyString(), anyString(), anyString(), any())).thenReturn(GuardianPhoneProvider.Result.MISMATCH);
		for (int attempt = 0; attempt < 3; attempt++) { service.verify(new GuardianWebDtos.Code(token, "999999"), ip()); }
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.REVIEW_REQUIRED);
		assertThat(row(token).reason()).isEqualTo(GuardianWebRequest.Reason.CODE_MISMATCH);
		assertError(() -> service.verify(new GuardianWebDtos.Code(token, "999999"), ip()), ErrorCode.GUARDIAN_STATE_CONFLICT);
		var disputed = service.dispute(new GuardianWebDtos.Token(token), ip());
		assertThat(disputed.exceptionReason()).isEqualTo(GuardianWebRequest.Reason.DISPUTED);
		assertThat(disputed.phoneControlConfirmed()).isFalse(); assertThat(disputed.guardianRelationshipVerified()).isFalse();
		assertThat(users.findAll()).allMatch(account -> account.getAgeVerificationState() == AgeVerificationState.UNKNOWN);
	}

	@Test
	void unknownProviderOutcomeIsPersistedAndNeverRetriedAutomatically() {
		String token = issue(user());
		doThrow(new IllegalStateException("synthetic private response +12025550123")).when(provider).send(anyString(), anyString(), any());
		var result = consent(token);
		assertThat(result.state()).isEqualTo(GuardianWebRequest.State.REVIEW_REQUIRED);
		assertThat(result.exceptionReason()).isEqualTo(GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN);
		assertThat(result.guardianRelationshipVerified()).isFalse();
		assertError(() -> consent(token), ErrorCode.GUARDIAN_STATE_CONFLICT);
		verify(provider, times(1)).send(anyString(), anyString(), any());
	}

	@Test
	void nullProviderReceiptOrVerificationCannotProducePhoneProof() {
		String first = issue(user()); doReturn(null).when(provider).send(anyString(), anyString(), any());
		assertThat(consent(first).exceptionReason()).isEqualTo(GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN);
		doReturn(new GuardianPhoneProvider.Receipt("synthetic-ref")).when(provider).send(anyString(), anyString(), any());
		String second = issue(user()); consent(second);
		when(provider.verify(anyString(), anyString(), anyString(), any())).thenReturn(null);
		assertThat(service.verify(new GuardianWebDtos.Code(second, "123456"), ip()).exceptionReason())
			.isEqualTo(GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN);
		assertThat(row(second).phoneConfirmedAt()).isNull();
	}

	@Test
	void persistedAbandonedAttemptsRecoverToUncertaintyWithoutRepeatingIo() {
		String sending = issue(user()); persistence.consent(GuardianWebSecrets.hash(sending), "synthetic-v1", "a".repeat(64));
		String verifying = issue(user()); consent(verifying); persistence.verify(GuardianWebSecrets.hash(verifying));
		String pending = issue(user()); consent(pending);
		clearInvocations(provider);
		when(clock.instant()).thenReturn(baseline.plusSeconds(301));
		new GuardianWebRecoveryScheduler(persistence).recover();
		assertThat(row(sending).reason()).isEqualTo(GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN);
		assertThat(row(verifying).reason()).isEqualTo(GuardianWebRequest.Reason.PROVIDER_RESULT_UNKNOWN);
		assertThat(row(pending).reason()).isEqualTo(GuardianWebRequest.Reason.EXPIRED);
		verifyNoInteractions(provider);
	}

	@Test
	void concurrentConsentCannotDuplicateSmsWhileFirstSendIsOutsideTransaction() throws Exception {
		String token = issue(user()); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return new GuardianPhoneProvider.Receipt("synthetic-ref");
		}).when(provider).send(anyString(), anyString(), any());
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> consent(token)); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			assertError(() -> consent(token), ErrorCode.GUARDIAN_STATE_CONFLICT);
			release.countDown(); assertThat(first.get(10, TimeUnit.SECONDS).state()).isEqualTo(GuardianWebRequest.State.PHONE_PENDING);
		} finally { release.countDown(); }
		verify(provider, times(1)).send(anyString(), anyString(), any());
	}

	@Test
	void withdrawalDuringPhoneCheckCancelsRequestAndDiscardsLateConfirmation() throws Exception {
		User user = user(); String token = issue(user); consent(token);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		when(provider.verify(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return GuardianPhoneProvider.Result.PHONE_CONFIRMED;
		});
		try (var pool = Executors.newSingleThreadExecutor()) {
			var verification = pool.submit(() -> service.verify(new GuardianWebDtos.Code(token, "123456"), ip()));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			userService.withdrawGoogle(user.getId(), user.getGoogleSub()); release.countDown();
			assertThatThrownBy(() -> verification.get(10, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
		} finally { release.countDown(); }
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.CANCELLED);
		assertThat(row(token).phoneConfirmedAt()).isNull();
		assertThat(jdbc.queryForObject("select phone_fingerprint from guardian_web_requests where token_hash=?", String.class, GuardianWebSecrets.hash(token))).isNull();
		assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
	}

	@Test
	void reissueDuringSendDiscardsItsLateReceipt() throws Exception {
		User user = user(); String old = issue(user);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		doAnswer(call -> {
			entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return new GuardianPhoneProvider.Receipt("synthetic-ref");
		}).when(provider).send(anyString(), anyString(), any());
		try (var pool = Executors.newSingleThreadExecutor()) {
			var sending = pool.submit(() -> consent(old)); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			String fresh = issue(user); release.countDown();
			assertThatThrownBy(() -> sending.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(BusinessException.class);
			assertThat(row(fresh).state()).isEqualTo(GuardianWebRequest.State.AWAITING_CONSENT);
		} finally { release.countDown(); }
		assertThat(row(old).state()).isEqualTo(GuardianWebRequest.State.CANCELLED);
		assertThat(row(old).providerReference()).isNull();
	}

	@Test
	void withdrawalHookRollbackRestoresPendingRequestTogetherWithAccount() {
		User user = user(); String token = issue(user); consent(token);
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			userService.withdrawGoogle(user.getId(), user.getGoogleSub()); tx.setRollbackOnly();
		});
		assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.PHONE_PENDING);
		assertThat(row(token).providerReference()).isEqualTo("synthetic-ref");
	}

	@Test
	void legacySuspendedAndWithdrawnAccountsCannotUseLink() {
		User legacy = user(); jdbc.update("update users set access_cohort='LEGACY_EXEMPT' where id=?", legacy.getId());
		assertError(() -> issue(legacy), ErrorCode.GUARDIAN_STATE_CONFLICT);
		User suspended = user(); String token = issue(suspended);
		suspended.suspend("Synthetic suspension", 1L, baseline); users.saveAndFlush(suspended);
		assertError(() -> service.view(new GuardianWebDtos.Token(token), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
		assertError(() -> issue(suspended), ErrorCode.ACCOUNT_SUSPENDED);
		User withdrawn = user(); withdrawn.withdraw(); users.saveAndFlush(withdrawn);
		assertError(() -> issue(withdrawn), ErrorCode.USER_INACTIVE);
	}

	@Test
	void serviceRejectsCallerTransactionBeforeIssuingOrSending() {
		User user = user();
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> issue(user)))
			.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
		assertThat(requests.findByUser_IdOrderByIssuedAtDescIdDesc(user.getId())).isEmpty();
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	void freshServiceCannotResetDurableAccountLinkBudget() {
		User user = user(); issue(user); issue(user); issue(user);
		var freshService = new GuardianWebService(policy, provider, persistence, secrets);
		assertError(() -> freshService.issue(user.getId(), ip()), ErrorCode.RATE_LIMIT_EXCEEDED);
		assertThat(requests.findByUser_IdOrderByIssuedAtDescIdDesc(user.getId())).hasSize(3);
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	void confirmedPhoneCanBeDisputedAndExpiredAwaitingLinkIsCancelled() {
		String proof = issue(user()); consent(proof); service.verify(new GuardianWebDtos.Code(proof, "123456"), ip());
		var disputed = service.dispute(new GuardianWebDtos.Token(proof), ip());
		assertThat(disputed.state()).isEqualTo(GuardianWebRequest.State.REVIEW_REQUIRED);
		assertThat(disputed.phoneControlConfirmed()).isFalse(); assertThat(disputed.guardianRelationshipVerified()).isFalse();
		String awaiting = issue(user()); clearInvocations(provider);
		when(clock.instant()).thenReturn(baseline.plusSeconds(1801)); new GuardianWebRecoveryScheduler(persistence).recover();
		assertThat(row(awaiting).state()).isEqualTo(GuardianWebRequest.State.CANCELLED);
		verifyNoInteractions(provider);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_WEB_MYSQL", matches = "true")
	void mysqlSnapshotBeforeUserLockCannotReactivateReissuedLink() throws Exception {
		User user = user(); String old = issue(user);
		var prepared = new CountDownLatch(1); var snapshot = new CountDownLatch(1); var release = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var reissue = pool.submit(() -> repeatableRead().execute(tx -> {
				var link = target().issue(user.getId()); pauseBeforeCommit(prepared, release); return link;
			})); assertThat(prepared.await(10, TimeUnit.SECONDS)).isTrue();
			var staleConsent = pool.submit(() -> repeatableRead().execute(tx -> {
				captureTokenSnapshot(old, snapshot); return target().consent(GuardianWebSecrets.hash(old), "synthetic-v1", "a".repeat(64));
			})); assertThat(snapshot.await(10, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> staleConsent.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			release.countDown(); reissue.get(10, TimeUnit.SECONDS);
			assertThatThrownBy(() -> staleConsent.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(BusinessException.class);
		} finally { release.countDown(); }
		assertThat(row(old).state()).isEqualTo(GuardianWebRequest.State.CANCELLED);
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_WEB_MYSQL", matches = "true")
	void mysqlSnapshotBeforeUserLockCannotPrepareTwoSmsSends() throws Exception {
		String token = issue(user());
		var prepared = new CountDownLatch(1); var snapshot = new CountDownLatch(1); var release = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(() -> repeatableRead().execute(tx -> {
				var attempt = target().consent(GuardianWebSecrets.hash(token), "synthetic-v1", "a".repeat(64));
				pauseBeforeCommit(prepared, release); return attempt;
			})); assertThat(prepared.await(10, TimeUnit.SECONDS)).isTrue();
			var second = pool.submit(() -> repeatableRead().execute(tx -> {
				captureTokenSnapshot(token, snapshot); return target().consent(GuardianWebSecrets.hash(token), "synthetic-v1", "a".repeat(64));
			})); assertThat(snapshot.await(10, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			release.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).nonce()).isNotBlank();
			assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(BusinessException.class);
		} finally { release.countDown(); }
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.SENDING);
		// Only persistence prepare is under test; no external send is executed by this race fixture.
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_WEB_MYSQL", matches = "true")
	void mysqlSnapshotBeforeRecoveryLockCannotRestoreCancelledPhoneIdentifiers() throws Exception {
		User user = user(); String token = issue(user); consent(token); String id = row(token).id();
		when(clock.instant()).thenReturn(baseline.plusSeconds(301)); clearInvocations(provider);
		var prepared = new CountDownLatch(1); var snapshot = new CountDownLatch(1); var release = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var reissue = pool.submit(() -> repeatableRead().execute(tx -> {
				var link = target().issue(user.getId()); pauseBeforeCommit(prepared, release); return link;
			})); assertThat(prepared.await(10, TimeUnit.SECONDS)).isTrue();
			var recovery = pool.submit(() -> repeatableRead().executeWithoutResult(tx -> {
				requests.ownerOfId(id); assertRepeatableRead(); snapshot.countDown(); target().expire(id);
			})); assertThat(snapshot.await(10, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> recovery.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			release.countDown(); reissue.get(10, TimeUnit.SECONDS); recovery.get(10, TimeUnit.SECONDS);
		} finally { release.countDown(); }
		assertThat(row(token).state()).isEqualTo(GuardianWebRequest.State.CANCELLED);
		assertThat(row(token).providerReference()).isNull();
		assertThat(jdbc.queryForObject("select phone_fingerprint from guardian_web_requests where id=?", String.class, id)).isNull();
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "GUARDIAN_WEB_MYSQL", matches = "true")
	void mysqlDifferentNewUsersCanIssueFirstLinksWithoutGapDeadlock() throws Exception {
		User first = user(), second = user();
		assertThat(requests.count()).isZero();
		var read = new CountDownLatch(2); var release = new CountDownLatch(1);
		doAnswer(call -> {
			// Production issue has acquired its User lock and completed the empty request lookup.
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			assertRepeatableRead(); read.countDown();
			assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return call.callRealMethod();
		}).when(secrets).token();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var firstLink = pool.submit(() -> persistence.issue(first.getId()));
			var secondLink = pool.submit(() -> persistence.issue(second.getId()));
			assertThat(read.await(10, TimeUnit.SECONDS)).isTrue(); release.countDown();
			var firstRow = row(firstLink.get(10, TimeUnit.SECONDS).url().split("#token=", 2)[1]);
			var secondRow = row(secondLink.get(10, TimeUnit.SECONDS).url().split("#token=", 2)[1]);
			assertThat(firstRow.userId()).isEqualTo(first.getId());
			assertThat(secondRow.userId()).isEqualTo(second.getId());
			assertThat(firstRow.id()).isNotEqualTo(secondRow.id());
			assertThat(firstRow.state()).isEqualTo(GuardianWebRequest.State.AWAITING_CONSENT);
			assertThat(secondRow.state()).isEqualTo(GuardianWebRequest.State.AWAITING_CONSENT);
		} finally { release.countDown(); }
		assertThat(requests.count()).isEqualTo(2);
		verify(provider, never()).send(anyString(), anyString(), any());
	}

	private GuardianWebPersistence target() {
		return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(persistence);
	}
	private TransactionTemplate repeatableRead() {
		var template = new TransactionTemplate(transactions);
		template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
		return template;
	}
	private void pauseBeforeCommit(CountDownLatch prepared, CountDownLatch release) {
		prepared.countDown();
		try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Synthetic barrier interrupted"); }
	}
	private void captureTokenSnapshot(String token, CountDownLatch snapshot) {
		// The production owner projection is a consistent read before User lock acquisition.
		requests.ownerOfToken(GuardianWebSecrets.hash(token)); assertRepeatableRead(); snapshot.countDown();
	}
	private void assertRepeatableRead() {
		assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
	}

	private User user() {
		User user = User.createGoogle("synthetic-" + UUID.randomUUID() + "@example.com", "hash", "Synthetic", UserRole.LEARNER,
			null, false, null, null, null, "synthetic-sub-" + UUID.randomUUID());
		user.recordSignupDateOfBirth(LocalDate.of(2014, 1, 1)); return users.saveAndFlush(user);
	}
	private String issue(User user) { return service.issue(user.getId(), ip()).url().split("#token=", 2)[1]; }
	private GuardianWebDtos.Status consent(String token) { return service.consent(new GuardianWebDtos.Consent(token,
		"synthetic-v1", true, true, "+12025550123"), ip()); }
	private String ip() { return "synthetic-" + UUID.randomUUID(); }
	private GuardianWebRequest row(String token) { return requests.findByTokenHash(GuardianWebSecrets.hash(token)).orElseThrow(); }
	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(expected));
	}
}
