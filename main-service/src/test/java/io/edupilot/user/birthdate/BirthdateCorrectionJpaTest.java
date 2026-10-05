package io.edupilot.user.birthdate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.VerifiedTestUsers;
import io.edupilot.admin.AdminAuditInterceptor;
import io.edupilot.ai.AiClient;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.JwtProperties;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.logging.AccessLogFilter;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.mail.EmailService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserService;
import io.edupilot.user.UserStatus;

/** Disposable real JPA/security tests; no provider, email or real account operations. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:birthdate-correction;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/birthdate-correction",
	"edupilot.mail.enabled=false", "edupilot.guardian.web.enabled=false"
})
@ActiveProfiles("jpa-context")
class BirthdateCorrectionJpaTest {
	private static final LocalDate ORIGINAL = LocalDate.of(2012, 12, 31);
	private static final LocalDate REQUESTED = LocalDate.of(2011, 12, 31);
	private static final String SELF = "/api/users/me/birthdate-correction-requests";
	private static final String ADMIN = "/api/admin/birthdate-correction-requests";
	@Autowired private WebApplicationContext context;
	@Autowired private UserRepository users;
	@Autowired private BirthdateCorrectionRepository requests;
	@Autowired private BirthdateCorrectionService service;
	@Autowired private UserService userService;
	@Autowired private JwtProperties jwtProperties;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private AccessLogFilter accessLog;
	@Autowired private io.edupilot.deletion.DeletionJournalLockRepository deletionLocks;
	@MockitoBean private Clock clock;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailService mail;
	private MockMvc mvc;

	@DynamicPropertySource static void optionalIsolatedMysql(DynamicPropertyRegistry properties) {
		String url = System.getenv("BIRTHDATE_CORRECTION_MYSQL_URL");
		if (url == null || url.isBlank()) return;
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/birthdate_correction_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Birthdate correction tests require the owned disposable loopback schema");
		}
		properties.add("spring.datasource.url", () -> url);
		properties.add("spring.datasource.username", () -> "root");
		properties.add("spring.datasource.password", () -> "");
		properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}

	@BeforeEach void setup() {
		when(clock.instant()).thenReturn(Instant.parse("2026-10-05T00:00:00Z"));
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		if (System.getenv("BIRTHDATE_CORRECTION_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("birthdate_correction_synthetic");
		}
		requests.deleteAll();
		if (!deletionLocks.existsById(1)) deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial());
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilters(accessLog).build();
	}

	@ParameterizedTest @ValueSource(booleans = {false, true})
	void unverifiedLocalAndGoogleAccountsCanRequestButCannotChangeDobOrUnlockBusiness(boolean google) throws Exception {
		User user = actor(UserRole.LEARNER, google, false);
		mvc.perform(get(SELF).header("Authorization", bearer(user))).andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
		mvc.perform(post(SELF).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body(REQUESTED)))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.data.userId").value(user.getId())).andExpect(jsonPath("$.data.state").value("PENDING"));
		mvc.perform(get(SELF).header("Authorization", bearer(user))).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.requestedDateOfBirth").value(REQUESTED.toString())).andExpect(header().string("Cache-Control", "no-store"));
		assertCapturedUnchanged(user);
		mvc.perform(get("/api/materials").header("Authorization", bearer(user))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		user.verifyEmail(clock.instant()); users.saveAndFlush(user);
		mvc.perform(get("/api/materials").header("Authorization", bearer(user))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AGE_VERIFICATION_REQUIRED"));
		assertCapturedUnchanged(user);
		verifyNoInteractions(ai, mail);
	}

	@Test void duplicateSameDateIsIdempotentAndConflictingDateCannotOverwritePendingRequest() {
		User user = actor(UserRole.LEARNER, false, false);
		var first = service.submit(user.getId(), REQUESTED);
		var retry = service.submit(user.getId(), REQUESTED);
		assertThat(retry.id()).isEqualTo(first.id()); assertThat(retry.requestedAt()).isEqualTo(first.requestedAt());
		assertThat(requests.count()).isEqualTo(1);
		assertError(() -> service.submit(user.getId(), REQUESTED.minusDays(1)), ErrorCode.BIRTHDATE_CORRECTION_PENDING);
		assertThat(service.mine(user.getId()).requestedDateOfBirth()).isEqualTo(REQUESTED);
		assertCapturedUnchanged(user);
	}

	@Test void openApiDocumentsTheReadOnlyCorrectionIntakeAndNewConflictWithoutAddingDobToUserResponse() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['"+SELF+"'].post.responses['409'].description").value(org.hamcrest.Matchers.containsString("BIRTHDATE_CORRECTION_PENDING")))
			.andExpect(jsonPath("$.paths['"+SELF+"'].get").exists())
			.andExpect(jsonPath("$.paths['"+ADMIN+"'].get").exists())
			.andExpect(jsonPath("$.paths['"+ADMIN+"/{id}'].get").exists())
			.andExpect(jsonPath("$.paths['"+ADMIN+"/{id}'].patch").doesNotExist())
			.andExpect(jsonPath("$.components.schemas.UserResponse.properties.dateOfBirth").doesNotExist());
	}

	@Test void concurrentIdenticalRequestsSerializeOnTheUserAndCreateOneDurableIntake() throws Exception {
		User user = actor(UserRole.LEARNER, false, false);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(6)) {
			var calls = new ArrayList<Future<BirthdateCorrectionDtos.Request>>();
			for (int i = 0; i < 6; i++) calls.add(executor.submit(() -> { start.await(); return service.submit(user.getId(), REQUESTED); }));
			start.countDown();
			Long first = calls.getFirst().get(15, TimeUnit.SECONDS).id();
			for (var call : calls) assertThat(call.get(15, TimeUnit.SECONDS).id()).isEqualTo(first);
		} finally { start.countDown(); }
		assertThat(requests.count()).isEqualTo(1); assertCapturedUnchanged(user);
	}

	@Test void futureMissingAndUnchangedDateDoNotCreateAnIntake() throws Exception {
		User user = actor(UserRole.LEARNER, false, false);
		for (String json : new String[]{"{}", body(LocalDate.of(2026, 10, 6)), body(ORIGINAL)}) {
			mvc.perform(post(SELF).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		mvc.perform(post(SELF).header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestedDateOfBirth\":\"2026-02-29\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		assertThat(requests.count()).isZero(); assertCapturedUnchanged(user); verifyNoInteractions(ai, mail);
	}

	@Test void selfEndpointsRequireAuthenticationAndUsePrincipalInsteadOfSubmittedOwner() throws Exception {
		User owner = actor(UserRole.LEARNER, false, false);
		User other = actor(UserRole.LEARNER, false, true);
		mvc.perform(get(SELF)).andExpect(status().isUnauthorized());
		mvc.perform(post(SELF).contentType(MediaType.APPLICATION_JSON).content(body(REQUESTED))).andExpect(status().isUnauthorized());
		var own = service.submit(owner.getId(), REQUESTED);
		mvc.perform(get(SELF).header("Authorization", bearer(other)).param("userId", owner.getId().toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
		mvc.perform(get(SELF + "/" + own.id()).header("Authorization", bearer(other))).andExpect(status().isNotFound());
		String spoof = "{\"userId\":" + owner.getId() + ",\"requestedDateOfBirth\":\"2010-01-01\"}";
		mvc.perform(post(SELF).header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content(spoof))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(other.getId()));
		assertThat(service.mine(owner.getId()).requestedDateOfBirth()).isEqualTo(REQUESTED);
	}

	@Test void adminIntakeIsReadOnlyBoundedAndAuditedWithoutDobInLogs() throws Exception {
		User admin = actor(UserRole.ADMIN, false, true);
		var first = service.submit(actor(UserRole.LEARNER, false, false).getId(), REQUESTED);
		var second = service.submit(actor(UserRole.INSTRUCTOR, true, false).getId(), LocalDate.of(2000, 2, 29));
		Logger audit = (Logger) LoggerFactory.getLogger(AdminAuditInterceptor.class);
		Logger http = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
		var logs = new ListAppender<ILoggingEvent>(); logs.start(); audit.addAppender(logs); http.addAppender(logs);
		try {
			mvc.perform(get(ADMIN).header("Authorization", bearer(admin)).param("size", "1")).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.totalElements").value(2))
				.andExpect(jsonPath("$.data.requests[0].id").value(first.id())).andExpect(jsonPath("$.data.totalPages").value(2));
			mvc.perform(get(ADMIN).header("Authorization", bearer(admin)).param("size", "1").param("page", "1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.requests[0].id").value(second.id()));
			mvc.perform(get(ADMIN + "/" + first.id()).header("Authorization", bearer(admin))).andExpect(status().isOk());
			for (String invalid : new String[]{"0", "101"}) mvc.perform(get(ADMIN).header("Authorization", bearer(admin)).param("size", invalid)).andExpect(status().isBadRequest());
			mvc.perform(get(ADMIN + "/999999999").header("Authorization", bearer(admin))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("BIRTHDATE_CORRECTION_NOT_FOUND"));
			mvc.perform(patch(ADMIN + "/" + first.id()).header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(body(REQUESTED)))
				.andExpect(status().isMethodNotAllowed());
			String captured = logs.list.stream().map(event -> event.getFormattedMessage() + event.getKeyValuePairs()).reduce("", String::concat);
			assertThat(captured).contains("BIRTHDATE_CORRECTION_LIST", "BIRTHDATE_CORRECTION_DETAIL", "targetId=\"" + first.id() + "\"")
				.doesNotContain(REQUESTED.toString(), "2000-02-29", "requestedDateOfBirth");
		} finally { audit.detachAppender(logs); http.detachAppender(logs); logs.stop(); }
	}

	@ParameterizedTest @ValueSource(strings = {"LEARNER", "INSTRUCTOR"})
	void otherRolesCannotReadAdministratorIntake(String role) throws Exception {
		User user = actor(UserRole.valueOf(role), false, true);
		mvc.perform(get(ADMIN).header("Authorization", bearer(user))).andExpect(status().isForbidden());
		assertError(() -> service.pending(user.getId(), 0, 20), ErrorCode.ACCESS_DENIED);
	}

	@Test void revokedAdminAuthorityIsRecheckedForOldJwtAndDirectService() throws Exception {
		User admin = actor(UserRole.ADMIN, false, true); String old = bearer(admin);
		jdbc.update("update users set role='LEARNER' where id=?", admin.getId());
		mvc.perform(get(ADMIN).header("Authorization", old)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
		assertError(() -> service.pending(admin.getId(), 0, 20), ErrorCode.ACCESS_DENIED);
	}

	@Test void suspensionAndDeletionPreventRequestsFromReopeningTheAccount() throws Exception {
		User user = actor(UserRole.LEARNER, false, false); String token = bearer(user);
		jdbc.update("update users set status='SUSPENDED' where id=?", user.getId());
		mvc.perform(post(SELF).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body(REQUESTED)))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
		assertError(() -> service.submit(user.getId(), REQUESTED), ErrorCode.ACCOUNT_SUSPENDED);
		jdbc.update("update users set status='DELETED' where id=?", user.getId());
		assertError(() -> service.submit(user.getId(), REQUESTED), ErrorCode.USER_INACTIVE);
		assertThat(requests.count()).isZero();
	}

	@Test void withdrawalErasesTheRequestDobAndRemovesItFromThePendingQueue() {
		User user = actor(UserRole.LEARNER, true, false);
		var request = service.submit(user.getId(), REQUESTED);
		userService.withdrawGoogle(user.getId(), user.getGoogleSub());
		var row = requests.findById(request.id()).orElseThrow();
		assertThat(row.getRequestedDateOfBirth()).isNull(); assertThat(row.getState()).isEqualTo(BirthdateCorrectionRequest.State.WITHDRAWN);
		assertThat(users.findById(user.getId()).orElseThrow().getDateOfBirth()).isNull();
		assertThat(service.pending(actor(UserRole.ADMIN, false, true).getId(), 0, 20).totalElements()).isZero();
		assertError(() -> service.submit(user.getId(), REQUESTED), ErrorCode.USER_INACTIVE);
	}

	@Test void concurrentSubmissionAndWithdrawalCannotKeepPersonalDobOrResurrectTheUser() throws Exception {
		User user = actor(UserRole.LEARNER, true, false);
		CountDownLatch started = new CountDownLatch(2);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var tasks = new ArrayList<Future<?>>();
			new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
				users.findByIdForUpdate(user.getId()).orElseThrow();
				tasks.add(executor.submit(() -> { started.countDown(); return service.submit(user.getId(), REQUESTED); }));
				tasks.add(executor.submit(() -> { started.countDown(); userService.withdrawGoogle(user.getId(), user.getGoogleSub()); return null; }));
				try {
					assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
					for (var task : tasks) assertThatThrownBy(() -> task.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
			});
			try { tasks.getFirst().get(15, TimeUnit.SECONDS); }
			catch (ExecutionException failure) { assertThat(failure.getCause()).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_INACTIVE)); }
			tasks.get(1).get(15, TimeUnit.SECONDS);
		}
		assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DELETED);
		requests.findByUser_Id(user.getId()).ifPresent(row -> {
			assertThat(row.getRequestedDateOfBirth()).isNull(); assertThat(row.getState()).isEqualTo(BirthdateCorrectionRequest.State.WITHDRAWN);
		});
	}

	private User actor(UserRole role, boolean google, boolean legacy) {
		String email = "synthetic-birthdate-" + UUID.randomUUID() + "@example.com";
		User user = google ? User.createGoogle(email, "synthetic-hash", "Synthetic", role, null, false, null, null, null, "synthetic-sub-" + UUID.randomUUID())
			: User.create(email, "synthetic-hash", "Synthetic", role);
		user.recordSignupDateOfBirth(ORIGINAL);
		if (legacy) VerifiedTestUsers.legacyVerified(user);
		return users.saveAndFlush(user);
	}
	private void assertCapturedUnchanged(User user) {
		User current = users.findById(user.getId()).orElseThrow();
		assertThat(current.getDateOfBirth()).isEqualTo(ORIGINAL); assertThat(current.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
	}
	private String bearer(User user) {
		// Keep JWT expiry valid in wall time while the birthdate policy uses an independently fixed KST date.
		return "Bearer " + new JwtTokenProvider(jwtProperties, Clock.systemUTC()).createAccessToken(user);
	}
	private static String body(LocalDate date) { return "{\"requestedDateOfBirth\":\"" + date + "\"}"; }
	private static void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
	}
}
