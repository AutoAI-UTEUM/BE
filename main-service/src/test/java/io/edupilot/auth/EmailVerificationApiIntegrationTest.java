package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.mail.*;
import io.edupilot.user.*;
import io.edupilot.user.dto.UpdatePreferencesRequest;
import io.edupilot.user.dto.UpdateProfileRequest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:email-verify-api;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/email-verification", "edupilot.mail.enabled=true",
	"edupilot.mail.provider=logging", "edupilot.mail.base-url=https://dev.uteum.com",
	"edupilot.mail.outbox.recovery-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationApiIntegrationTest {
	@Autowired private io.edupilot.deletion.DeletionJournalLockRepository deletionLocks;
	@DynamicPropertySource static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url=System.getenv("VERIFICATION_MYSQL_URL");
		if(url==null||url.isBlank()){return;}
		if(!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/verification_synthetic(?:\\?.*)?$")){throw new IllegalArgumentException("Verification tests require disposable loopback database");}
		settings.add("spring.datasource.url",()->url);settings.add("spring.datasource.username",()->"root");
		settings.add("spring.datasource.password",()->"");settings.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
	}
	private static final AtomicInteger IPS = new AtomicInteger();
	private static final Pattern TOKEN = Pattern.compile("/verify-email\\?token=([A-Za-z0-9_-]{43})");
	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traces;
	@Autowired private UserRepository users;
	@Autowired private UserService userService;
	@Autowired private EmailVerificationTokenRepository tokens;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailOutboxRepository outbox;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@Autowired private AuthSessionRepository authSessions;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private JwtTokenProvider jwt;
	@MockitoSpyBean private PasswordEncoder passwords;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Clock clock;
	@Autowired private EmailVerificationGate gate;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private EmailSender sender;
	@MockitoBean private AiClient ai;
	@MockitoBean private GoogleIdTokenVerifier google;
	private final List<EmailMessage> messages = new CopyOnWriteArrayList<>();
	private MockMvc mvc;
	private String ip;

	@BeforeEach
	void seedDeletionJournal() { if(!deletionLocks.existsById(1)) { deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial()); } }
	@BeforeEach void setup() {
		if(System.getenv("VERIFICATION_MYSQL_URL")!=null){
			assertThat(jdbc.queryForObject("select @@port",Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()",String.class)).isEqualTo("verification_synthetic");
		}
		// Hibernate create-drop omits V55's cross-column evidence CHECK. Exercise that
		// same production invariant in both the H2 and disposable MySQL API fixtures.
		String schema = System.getenv("VERIFICATION_MYSQL_URL") == null ? "current_schema" : "database()";
		if (jdbc.queryForObject("select count(*) from information_schema.table_constraints where lower(table_name)='users' and lower(constraint_name)='chk_user_email_verified_evidence' and lower(table_schema)=lower(" + schema + ")", Integer.class) == 0) {
			jdbc.execute("alter table users add constraint chk_user_email_verified_evidence check ((email_verification_state='VERIFIED' and email_verified_at is not null) or (email_verification_state in ('UNKNOWN','PENDING') and email_verified_at is null))");
		}
		ip = "192.0.2." + IPS.incrementAndGet();
		messages.clear();
		when(sender.send(any())).thenAnswer(invocation -> {
			messages.add(invocation.getArgument(0));
			return new EmailDeliveryResult("synthetic-verify-receipt");
		});
		tokens.deleteAll(); outbox.deleteAll(); deliveries.deleteAll();
		refreshTokens.deleteAll(); authSessions.deleteAll(); users.deleteAll();
		if (!quotaLocks.existsById(1)) { quotaLocks.saveAndFlush(EmailQuotaLock.initial()); }
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(traces).build();
	}
	@AfterEach void awaitDispatchReceipt() throws InterruptedException {
		for (int attempt=0; attempt<250; attempt++) {
			if (deliveries.findAll().stream().noneMatch(delivery -> delivery.getStatus() == EmailDeliveryStatus.QUEUED)) { return; }
			Thread.sleep(20);
		}
		throw new AssertionError("Synthetic verification dispatch remained queued");
	}

	@Test void localSignupQueuesSecretLinkButDoesNotGrantLearningAccess(CapturedOutput output) throws Exception {
		MvcResult result = signup("signup-verify@example.com");
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("PENDING", "emailVerificationRequired").doesNotContain("token=", "verify-email?");
		User user = users.findByEmail("signup-verify@example.com").orElseThrow();
		String raw = token(user.getEmail());
		assertThat(tokens.findAll()).hasSize(1);
		assertThat(tokens.findAll().getFirst().getTokenHash()).isEqualTo(EmailVerificationService.hash(raw));
		assertThat(output).doesNotContain(raw);
		for (String endpoint : List.of("/api/materials", "/api/materials/1/file", "/api/sessions/1/stream", "/api/notes")) {
			mvc.perform(get(endpoint).header("Authorization", bearer(user)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"))
				.andExpect(header().string("Cache-Control", "no-store"));
		}
		mvc.perform(get("/api/users/me").header("Authorization", bearer(user))).andExpect(status().isOk());
		mvc.perform(get("/api/health")).andExpect(status().isOk());
		verifyNoInteractions(ai);
	}

	@Test void unknownAccountCanRequestAndConfirmThenUseTheSameJwtWithoutReplayingTheToken() throws Exception {
		User user = unknown("existing-verify@example.com");
		String bearer = bearer(user);
		mvc.perform(get("/api/auth/email-verification/status").header("Authorization", bearer))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerification").value("UNKNOWN"));
		request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		confirm(raw).andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerification").value("VERIFIED"))
			.andExpect(jsonPath("$.data.emailVerificationRequired").value(false)).andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(get("/api/materials").header("Authorization", bearer)).andExpect(status().isOk());
		confirm(raw).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_TOKEN_INVALID"));
		assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
	}

	@Test void anonymousRequestAndStatusAreRejectedButLinkGetDoesNotConfirm() throws Exception {
		mvc.perform(post("/api/auth/email-verification/request")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/auth/email-verification/status")).andExpect(status().isUnauthorized());
		User user = unknown("get-link-verify@example.com"); request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		mvc.perform(get("/api/auth/email-verification/confirm").param("token",raw)).andExpect(status().isMethodNotAllowed());
		assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
	}

	@Test void resendInvalidatesTheOlderLinkAndAlreadyVerifiedRequestDoesNotIssueAgain() throws Exception {
		User user = unknown("resend-verify@example.com"); request(user).andExpect(status().isAccepted());
		String old = token(user.getEmail());
		request(user).andExpect(status().isAccepted());
		String newest = awaitLatestToken(user.getEmail(),2);
		assertThat(newest).isNotEqualTo(old);
		confirm(old).andExpect(status().isBadRequest()); confirm(newest).andExpect(status().isOk());
		request(user).andExpect(status().isAccepted());
		assertThat(tokens.count()).isEqualTo(2);
	}

	@Test void expiredAndChangedEmailTokensCannotConfirm() throws Exception {
		User user = unknown("expired-verify@example.com"); request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		jdbc.update("update email_verification_tokens set expires_at = ?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)));
		confirm(raw).andExpect(status().isBadRequest());
		request(user).andExpect(status().isAccepted()); String changed = awaitLatestToken(user.getEmail(),2);
		jdbc.update("update users set email = 'changed-verify@example.com' where id = ?",user.getId());
		confirm(changed).andExpect(status().isBadRequest());
		assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
	}

	@Test void withdrawnAccountCannotBeConfirmedUsingTheOldLink() throws Exception {
		User user = unknown("withdraw-verify@example.com"); request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		mvc.perform(delete("/api/users/me").header("Authorization",bearer(user)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"StrongPass123!\"}")).andExpect(status().isOk());
		confirm(raw).andExpect(status().isBadRequest());
		assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
	}

	@Test void concurrentConfirmationHasOneSuccessAndOneRejection() throws Exception {
		User user = unknown("race-verify@example.com"); request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail()); CountDownLatch start = new CountDownLatch(1);
		try (var executor=Executors.newFixedThreadPool(2)) {
			var first=executor.submit(()->{start.await();return confirm(raw).andReturn().getResponse().getStatus();});
			var second=executor.submit(()->{start.await();return confirm(raw).andReturn().getResponse().getStatus();});
			start.countDown();
			assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
		}
		assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
	}

	@Test void signupRollbackLeavesNoUserTokenOrSendablePayload() throws Exception {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			try { assertThat(signup("rollback-verify@example.com").getResponse().getStatus()).isEqualTo(200); }
			catch (Exception error) { throw new AssertionError(error); }
			assertThat(messages).isEmpty(); transaction.setRollbackOnly();
		});
		assertThat(users.count()).isZero(); assertThat(tokens.count()).isZero(); assertThat(outbox.count()).isZero();
		assertThat(messages).isEmpty();
		assertThat(deliveries.findAll().getFirst().getErrorSummary()).isEqualTo("CALLER_TRANSACTION_ROLLED_BACK");
	}

	@Test void googleSignupAlsoRemainsPendingAndQueuesItsOwnVerificationLink() throws Exception {
		when(google.verify("synthetic-google-token")).thenReturn(new GoogleProfile("synthetic-sub","google-verify@example.com","Synthetic"));
		mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
			.content("{\"idToken\":\"synthetic-google-token\",\"role\":\"LEARNER\",\"dateOfBirth\":\"2000-01-01\"}")
			.with(request->{request.setRemoteAddr(ip);return request;})).andExpect(status().isOk());
		User user=users.findByEmail("google-verify@example.com").orElseThrow(); token(user.getEmail());
		assertThat(user.getEmailVerificationState()).isEqualTo(EmailVerificationState.PENDING);
		assertThat(user.isEmailVerified()).isFalse();
	}

	@Test void concurrentResendAndConfirmationCannotReviveAnInvalidatedLink() throws Exception {
		User user=unknown("resend-race-verify@example.com");request(user).andExpect(status().isAccepted());
		String old=token(user.getEmail());CountDownLatch start=new CountDownLatch(1);
		try(var executor=Executors.newFixedThreadPool(2)){
			var confirmation=executor.submit(()->{start.await();return confirm(old).andReturn().getResponse().getStatus();});
			var resend=executor.submit(()->{start.await();return request(user).andReturn().getResponse().getStatus();});
			start.countDown();int confirmed=confirmation.get(15,TimeUnit.SECONDS);
			assertThat(resend.get(15,TimeUnit.SECONDS)).isEqualTo(202);
			assertThat(confirmed).isIn(200,400);
			if(confirmed==200){assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();assertThat(tokens.count()).isEqualTo(1);}
			else{assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();confirm(awaitLatestToken(user.getEmail(),2)).andExpect(status().isOk());}
		}
		confirm(old).andExpect(status().isBadRequest());
	}

	@Test void currentCommittedStateBlocksEvenWhenTheJwtAndCallerEntityWerePreviouslyVerified() throws Exception {
		User user=unknown("live-state-verify@example.com");request(user).andExpect(status().isAccepted());
		confirm(token(user.getEmail())).andExpect(status().isOk());String bearer=bearer(user);
		mvc.perform(get("/api/materials").header("Authorization",bearer)).andExpect(status().isOk());
		jdbc.update("update users set email_verification_state='PENDING', email_verified_at=null where id=?",user.getId());
		mvc.perform(get("/api/materials").header("Authorization",bearer)).andExpect(status().isForbidden());
		new TransactionTemplate(transactions).executeWithoutResult(transaction->{
			User uncommitted=users.findById(user.getId()).orElseThrow();uncommitted.verifyEmail(clock.instant());users.flush();
			org.assertj.core.api.Assertions.assertThatThrownBy(()->gate.requireVerified(user.getId()))
				.isInstanceOf(io.edupilot.global.error.BusinessException.class);
			transaction.setRollbackOnly();
		});
		assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isFalse();
	}

	@Test void pendingAccountCanRefreshItsSessionAndReadStatusButCannotUseLearningApi() throws Exception {
		User user=unknown("refresh-verify@example.com");
		MvcResult login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"refresh-verify@example.com\",\"password\":\"StrongPass123!\"}")
			.with(request->{request.setRemoteAddr(ip);return request;})).andExpect(status().isOk()).andReturn();
		String setCookie=login.getResponse().getHeader("Set-Cookie");assertThat(setCookie).isNotNull();
		String pair=setCookie.split(";",2)[0];int separator=pair.indexOf('=');
		var cookie=new jakarta.servlet.http.Cookie(pair.substring(0,separator),pair.substring(separator+1));
		MvcResult refreshed=mvc.perform(post("/api/auth/refresh").cookie(cookie)
			.with(request->{request.setRemoteAddr(ip);return request;})).andExpect(status().isOk()).andReturn();
		String access=new JsonMapper().readTree(refreshed.getResponse().getContentAsString()).get("data").get("accessToken").asText();
		mvc.perform(get("/api/auth/email-verification/status").header("Authorization","Bearer "+access))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerification").value("UNKNOWN"));
		mvc.perform(get("/api/sessions").header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
	}

	@ParameterizedTest
	@EnumSource(PendingAccountWrite.class)
	void unrelatedAccountUpdateCannotOverwriteAConcurrentConfirmation(PendingAccountWrite write) throws Exception {
		User user = unknown("update-race-" + write.name().toLowerCase(java.util.Locale.ROOT) + "@example.com");
		request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		String access = bearer(user);
		CountDownLatch pendingRead = new CountDownLatch(1);
		CountDownLatch confirmationCommitted = new CountDownLatch(1);
		try (var executor = Executors.newSingleThreadExecutor()) {
			var updated = executor.submit(() -> {
				new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
					// Keep T1's real managed PENDING entity while T2 confirms in its own transaction.
					assertThat(userService.me(user.getId()).emailVerification()).isEqualTo(EmailVerificationState.PENDING);
					pendingRead.countDown();
					try {
						assertThat(confirmationCommitted.await(15, TimeUnit.SECONDS)).isTrue();
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(interrupted);
					}
					switch (write) {
						case PROFILE -> userService.updateProfile(user.getId(), new UpdateProfileRequest("Changed", "Synthetic school"));
						case PREFERENCES -> userService.updatePreferences(user.getId(), new UpdatePreferencesRequest(false, false, AiAnswerStyle.CONCISE));
						case PASSWORD -> userService.changePassword(user.getId(), "StrongPass123!", "ChangedPass123!");
					}
				});
				return null;
			});
			try {
				assertThat(pendingRead.await(15, TimeUnit.SECONDS)).isTrue();
				confirm(raw).andExpect(status().isOk());
				assertThat(users.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
				confirmationCommitted.countDown();
				updated.get(15, TimeUnit.SECONDS);
			} finally {
				confirmationCommitted.countDown();
			}
		}
		User persisted = users.findById(user.getId()).orElseThrow();
		assertThat(persisted.isEmailVerified()).isTrue();
		assertThat(persisted.getEmailVerifiedAt()).isNotNull();
		switch (write) {
			case PROFILE -> assertThat(persisted.getName()).isEqualTo("Changed");
			case PREFERENCES -> assertThat(persisted.getAiAnswerStyle()).isEqualTo(AiAnswerStyle.CONCISE);
			case PASSWORD -> assertThat(passwords.matches("ChangedPass123!", persisted.getPasswordHash())).isTrue();
		}
		assertThat(tokens.findAll()).singleElement().satisfies(token -> assertThat(token.getUsedAt()).isNotNull());
		confirm(raw).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_TOKEN_INVALID"));
		mvc.perform(get("/api/materials").header("Authorization", access)).andExpect(status().isOk());
	}

	@Test
	void withdrawalAndConfirmationSerializeWhenWithdrawalHasAlreadyReadThePendingAccount() throws Exception {
		User user = unknown("withdraw-first-race@example.com");
		request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		CountDownLatch accountRead = new CountDownLatch(1);
		CountDownLatch allowWithdrawal = new CountDownLatch(1);
		doAnswer(invocation -> {
			// UserService has read its real managed User before checking the real BCrypt hash.
			accountRead.countDown();
			assertThat(allowWithdrawal.await(15, TimeUnit.SECONDS)).isTrue();
			return invocation.callRealMethod();
		}).when(passwords).matches(eq("StrongPass123!"), anyString());
		try (var executor = Executors.newFixedThreadPool(2)) {
			var withdrawn = executor.submit(() -> { userService.withdraw(user.getId(), "StrongPass123!"); return null; });
			try {
				assertThat(accountRead.await(15, TimeUnit.SECONDS)).isTrue();
				var confirmed = executor.submit(() -> confirm(raw).andReturn().getResponse().getStatus());
				Integer statusBeforeWithdrawal = null;
				try { statusBeforeWithdrawal = confirmed.get(500, TimeUnit.MILLISECONDS); }
				catch (TimeoutException waitingForAccountLock) { /* Expected with serialized writes. */ }
				allowWithdrawal.countDown();
				withdrawn.get(15, TimeUnit.SECONDS);
				assertThat(confirmed.get(15, TimeUnit.SECONDS)).isEqualTo(400);
				assertThat(statusBeforeWithdrawal).isNull();
			} finally { allowWithdrawal.countDown(); }
		}
		assertWithdrawnAndOldLinkRejected(user, raw);
	}

	@Test
	void withdrawalAfterAnUncommittedConfirmationClearsBothConfirmationColumns() throws Exception {
		User user = unknown("confirm-first-withdraw-race@example.com");
		request(user).andExpect(status().isAccepted());
		String raw = token(user.getEmail());
		CountDownLatch confirmationApplied = new CountDownLatch(1);
		CountDownLatch allowConfirmationCommit = new CountDownLatch(1);
		CountDownLatch withdrawalStarted = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var confirmed = executor.submit(() -> {
				new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
					try { confirm(raw).andExpect(status().isOk()); }
					catch (Exception failure) { throw new AssertionError(failure); }
					users.flush();
					confirmationApplied.countDown();
					try { assertThat(allowConfirmationCommit.await(15, TimeUnit.SECONDS)).isTrue(); }
					catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
				});
				return null;
			});
			try {
				assertThat(confirmationApplied.await(15, TimeUnit.SECONDS)).isTrue();
				var withdrawn = executor.submit(() -> {
					withdrawalStarted.countDown();
					userService.withdraw(user.getId(), "StrongPass123!");
					return null;
				});
				assertThat(withdrawalStarted.await(15, TimeUnit.SECONDS)).isTrue();
				org.assertj.core.api.Assertions.assertThatThrownBy(() -> withdrawn.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				allowConfirmationCommit.countDown();
				confirmed.get(15, TimeUnit.SECONDS);
				withdrawn.get(15, TimeUnit.SECONDS);
			} finally { allowConfirmationCommit.countDown(); }
		}
		assertThat(tokens.findAll()).singleElement().satisfies(token -> assertThat(token.getUsedAt()).isNotNull());
		assertWithdrawnAndOldLinkRejected(user, raw);
	}

	private void assertWithdrawnAndOldLinkRejected(User user, String raw) throws Exception {
		User persisted = users.findById(user.getId()).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(persisted.getEmailVerificationState()).isEqualTo(EmailVerificationState.UNKNOWN);
		assertThat(persisted.getEmailVerifiedAt()).isNull();
		confirm(raw).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_TOKEN_INVALID"));
		MvcResult denied = mvc.perform(get("/api/materials").header("Authorization", bearer(user)))
			.andExpect(status().is4xxClientError()).andReturn();
		assertThat(new JsonMapper().readTree(denied.getResponse().getContentAsString()).get("error").get("code").asText())
			.isIn("USER_INACTIVE", "TOKEN_INVALID");
		verifyNoInteractions(ai);
	}

	private enum PendingAccountWrite { PROFILE, PREFERENCES, PASSWORD }

	private User unknown(String email) { return users.saveAndFlush(User.create(email,passwords.encode("StrongPass123!"),"Synthetic",UserRole.LEARNER)); }
	private String bearer(User user) { return "Bearer "+jwt.createAccessToken(user); }
	private MvcResult signup(String email) throws Exception {
		return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\""+email+"\",\"password\":\"StrongPass123!\",\"name\":\"Synthetic\",\"role\":\"LEARNER\",\"dateOfBirth\":\"2000-01-01\"}")
			.with(request->{request.setRemoteAddr(ip);return request;})).andReturn();
	}
	private org.springframework.test.web.servlet.ResultActions request(User user) throws Exception {
		return mvc.perform(post("/api/auth/email-verification/request").header("Authorization",bearer(user))
			.with(request->{request.setRemoteAddr(ip);return request;}));
	}
	private org.springframework.test.web.servlet.ResultActions confirm(String raw) throws Exception {
		return mvc.perform(post("/api/auth/email-verification/confirm").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\""+raw+"\"}").with(request->{request.setRemoteAddr(ip);return request;}));
	}
	private String token(String email) throws InterruptedException { return awaitLatestToken(email,1); }
	private String awaitLatestToken(String email,int count) throws InterruptedException {
		for (int attempt=0;attempt<250;attempt++) {
			var matching=messages.stream().filter(message->message.to().equals(email)&&message.type()==EmailDeliveryType.EMAIL_VERIFY).toList();
			if(matching.size()>=count){var matcher=TOKEN.matcher(matching.getLast().textBody());assertThat(matcher.find()).isTrue();return matcher.group(1);}
			Thread.sleep(20);
		}
		throw new AssertionError("Synthetic verification mail not dispatched");
	}
}
