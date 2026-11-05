package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryResult;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailOutboxRepository;
import io.edupilot.mail.EmailOutboxStatus;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailQuotaLock;
import io.edupilot.mail.EmailQuotaLockRepository;
import io.edupilot.mail.EmailSender;
import io.edupilot.user.EmailVerificationState;
import io.edupilot.user.UserRepository;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:signup-mail-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-signup-mail",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/signup-mail-outbox",
	"edupilot.policy.signup-consent-required=false",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.outbox.dispatch.mode=NORMAL", "edupilot.mail.outbox.recovery-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SignupMailOutboxJpaTest {
	private static final String EMAIL = "signup-mail-synthetic@example.test";
	private static final String TOKEN = "synthetic-google-signup-token";
	private static final String FAILURE_CONSTRAINT = "chk_signup_mail_reject";
	private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url = System.getenv("SIGNUP_MAIL_MYSQL_URL");
		if (url == null || url.isBlank()) return;
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/signup_mail_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Signup mail tests require the disposable loopback database");
		}
		settings.add("spring.datasource.url", () -> url);
		settings.add("spring.datasource.username", () -> "root");
		settings.add("spring.datasource.password", () -> "");
		settings.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}

	@Autowired private WebApplicationContext context;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private UserRepository users;
	@Autowired private EmailVerificationTokenRepository tokens;
	@Autowired private AuthSessionRepository sessions;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@MockitoBean private GoogleIdTokenVerifier google;
	@MockitoBean private EmailSender sender;
	@MockitoBean private AiClient ai;
	@MockitoBean private Clock clock;
	@MockitoBean(name = "mailExecutor") private Executor executor;
	private final Deque<Runnable> pending = new ConcurrentLinkedDeque<>();
	private MockMvc mvc;

	@BeforeEach
	void prepareSyntheticDatabase() {
		if (System.getenv("SIGNUP_MAIL_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("signup_mail_synthetic");
		}
		assertThat(jdbc.execute((Connection c) -> c.getTransactionIsolation()))
			.isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
		jobs.deleteAll();
		deliveries.deleteAll();
		tokens.deleteAll();
		refreshTokens.deleteAll();
		sessions.deleteAll();
		users.deleteAll();
		if (!quotaLocks.existsById(1)) quotaLocks.saveAndFlush(EmailQuotaLock.initial());
		pending.clear();
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(google.verify(TOKEN)).thenReturn(new GoogleProfile("synthetic-signup-sub", EMAIL, "Synthetic learner"));
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("synthetic-signup-receipt"));
		doAnswer(call -> { pending.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void signupCommitsTokenAndOutboxBeforeDispatch(boolean googleSignup) throws Exception {
		assertThat(submit(googleSignup).getResponse().getStatus()).isEqualTo(200);
		var user = users.findByEmail(EMAIL).orElseThrow();
		assertThat(user.getEmailVerificationState()).isEqualTo(EmailVerificationState.PENDING);
		assertThat(user.isEmailVerificationRequired()).isTrue();
		assertThat(tokens.count()).isEqualTo(1);
		assertThat(jobs.findAll()).singleElement().satisfies(job -> assertThat(job.getStatus()).isEqualTo(EmailOutboxStatus.READY));
		assertThat(deliveries.findAll()).singleElement().satisfies(delivery -> assertThat(delivery.getType()).isEqualTo(EmailDeliveryType.EMAIL_VERIFY));
		assertThat(pending).hasSize(1);
		verifyNoInteractions(sender, ai);
		pending.removeFirst().run();
		assertThat(jobs.findAll()).singleElement().satisfies(job -> assertThat(job.getStatus()).isEqualTo(EmailOutboxStatus.SENT));
		assertThat(deliveries.findAll()).singleElement().satisfies(delivery -> assertThat(delivery.getAttemptCount()).isEqualTo(1));
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void callerRollbackRemovesAccountTokenAndOutboxWithoutSending(boolean googleSignup) {
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			// Establish the caller snapshot before the separately committed audit insert.
			jdbc.queryForObject("select count(*) from users", Long.class);
			try {
				assertThat(submit(googleSignup).getResponse().getStatus()).isEqualTo(200);
			} catch (Exception error) {
				throw new AssertionError(error);
			}
			assertThat(jobs.count()).isEqualTo(1);
			assertThat(pending).isEmpty();
			verifyNoInteractions(sender, ai);
			status.setRollbackOnly();
		});
		assertNoSignupStateOrDispatch();
		assertThat(deliveries.findAll()).singleElement().satisfies(delivery -> {
			assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
			assertThat(delivery.getErrorSummary()).isEqualTo("CALLER_TRANSACTION_ROLLED_BACK");
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void actualOutboxConstraintFailureRollsBackSignupAndKeepsFailedAudit(boolean googleSignup) throws Exception {
		jdbc.execute("alter table email_outbox add constraint " + FAILURE_CONSTRAINT + " check (delivery_id < 0)");
		MvcResult result;
		try {
			result = submit(googleSignup);
		} finally {
			jdbc.execute("alter table email_outbox drop "
				+ (System.getenv("SIGNUP_MAIL_MYSQL_URL") == null ? "constraint " : "check ") + FAILURE_CONSTRAINT);
		}
		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		Throwable cause = result.getResolvedException();
		while (cause != null && !(cause instanceof SQLException)) cause = cause.getCause();
		assertThat(cause).as("Failure must reach the real outbox INSERT constraint").isInstanceOf(SQLException.class);
		assertNoSignupStateOrDispatch();
		assertThat(deliveries.findAll()).singleElement().satisfies(delivery -> {
			assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
			assertThat(delivery.getErrorSummary()).isEqualTo("OUTBOX_QUEUE_FAILED");
		});
	}

	@Test
	void committedGuardianDeliveryCannotEnterOrdinaryOutboxBehindOlderSnapshot() {
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
			jdbc.queryForObject("select count(*) from users", Long.class);
			var independent = new TransactionTemplate(transactions);
			independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			Long id = independent.execute(tx -> deliveries.saveAndFlush(EmailDelivery.queued(
				new EmailMessage(EMAIL, "Synthetic guardian", "Synthetic body", null, EmailDeliveryType.GUARDIAN_TEAM_NOTICE), NOW)).getId());
			outbox.enqueue(id, new EmailMessage(EMAIL, "Synthetic ordinary", "Synthetic body", null, EmailDeliveryType.TEST), NOW.plusSeconds(1800));
		})).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("보호자 팀 확인 메일은 일반 outbox에 저장할 수 없습니다.");
		assertThat(jobs.count()).isZero();
		verifyNoInteractions(sender, ai);
	}

	private void assertNoSignupStateOrDispatch() {
		assertThat(users.findByEmail(EMAIL)).isEmpty();
		assertThat(tokens.count()).isZero();
		assertThat(jobs.count()).isZero();
		assertThat(sessions.count()).isZero();
		assertThat(refreshTokens.count()).isZero();
		assertThat(pending).isEmpty();
		verifyNoInteractions(sender, ai);
	}

	private MvcResult submit(boolean googleSignup) throws Exception {
		String body = googleSignup ? """
			{"idToken":"synthetic-google-signup-token","role":"LEARNER","dateOfBirth":"2000-01-01"}
			""" : """
			{"email":"signup-mail-synthetic@example.test","password":"syntheticPassword123",
			 "name":"Synthetic learner","role":"LEARNER","dateOfBirth":"2000-01-01"}
			""";
		return mvc.perform(post(googleSignup ? "/api/auth/google" : "/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
	}
}
