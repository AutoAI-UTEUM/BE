package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import jakarta.servlet.http.Cookie;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:password-reset-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/password-reset",
		"edupilot.admin.infra.enabled=false",
		"edupilot.mail.enabled=true",
		"edupilot.mail.provider=logging",
		"edupilot.mail.base-url=https://dev.uteum.com"
	}
)
@ActiveProfiles("jpa-context")
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetApiIntegrationTest {

	private static final Pattern LINK_TOKEN = Pattern.compile(
		"https://dev\\.uteum\\.com/reset-password\\?token=([A-Za-z0-9_-]{43})"
	);

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private UserRepository users;
	@Autowired private PasswordResetTokenRepository tokens;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private AuthSessionRepository sessions;
	@Autowired private PasswordResetCleanupScheduler cleanupScheduler;
	@Autowired private PasswordEncoder passwordEncoder;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		tokens.deleteAll();
		deliveries.deleteAll();
		refreshTokens.deleteAll();
		sessions.deleteAll();
		users.deleteAll();
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@AfterEach
	void awaitMailWorker() throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (deliveries.findAll().stream()
				.noneMatch(delivery -> delivery.getStatus() == EmailDeliveryStatus.QUEUED)) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Mail worker left reset delivery queued");
	}

	@Test
	void missingAndInactiveAccountsGetSame202WithoutTokenOrDelivery(CapturedOutput output)
		throws Exception {
		User active = saveUser("active-reset@example.com");
		User inactive = saveUser("inactive-reset@example.com");
		ReflectionTestUtils.setField(inactive, "status", UserStatus.DELETED);
		users.saveAndFlush(inactive);

		String activeBody = request(active.getEmail(), "192.0.2.1")
			.getResponse().getContentAsString();
		awaitToken(output, 1);
		long tokensBefore = tokens.count();
		long deliveriesBefore = deliveries.count();
		assertThat(request("missing-reset@example.com", "192.0.2.2")
			.getResponse().getContentAsString()).isEqualTo(activeBody);
		assertThat(request(inactive.getEmail(), "192.0.2.3")
			.getResponse().getContentAsString()).isEqualTo(activeBody);
		assertThat(tokens.count()).isEqualTo(tokensBefore);
		assertThat(deliveries.count()).isEqualTo(deliveriesBefore);
	}

	@Test
	void loggedLinkResetsPasswordAndRevokesRefreshAndAuthSession(CapturedOutput output)
		throws Exception {
		User user = saveUser("normal-reset@example.com");
		Cookie oldRefresh = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(user.getEmail(), "password123")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getCookie(RefreshTokenCookie.NAME);
		assertThat(oldRefresh).isNotNull();
		Cookie secondRefresh = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(user.getEmail(), "password123")))
			.andExpect(status().isOk())
			.andReturn().getResponse().getCookie(RefreshTokenCookie.NAME);
		assertThat(secondRefresh).isNotNull();
		assertThat(sessions.count()).isEqualTo(2);

		request(user.getEmail(), "192.0.2.4");
		String rawToken = awaitToken(output, 1);
		PasswordResetToken saved = tokens.findAll().getFirst();
		assertThat(saved.getTokenHash()).isEqualTo(PasswordResetService.hash(rawToken));
		assertThat(saved.getTokenHash()).doesNotContain(rawToken);
		assertThat(saved.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(29)));

		confirm(rawToken, "newPassword123", "192.0.2.4")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.message").value(
				"비밀번호가 변경되었습니다. 다시 로그인해주세요."
			));
		assertThat(tokens.findById(saved.getId()).orElseThrow().getUsedAt()).isNotNull();
		assertThat(sessions.findAll()).allMatch(AuthSession::isRevoked);
		assertThat(refreshTokens.findAll()).allMatch(RefreshToken::isRevoked);

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(user.getEmail(), "password123")))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(user.getEmail(), "newPassword123")))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/refresh").cookie(oldRefresh))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/refresh").cookie(secondRefresh))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void newerRequestInvalidatesPreviousLinkAndSuccessfulLinkIsSingleUse(CapturedOutput output)
		throws Exception {
		User user = saveUser("repeat-reset@example.com");
		request(user.getEmail(), "192.0.2.5");
		String first = awaitToken(output, 1);
		request(user.getEmail(), "192.0.2.5");
		String second = awaitToken(output, 2);
		assertThat(second).isNotEqualTo(first);

		assertInvalid(confirm(first, "newPassword123", "192.0.2.5"));
		confirm(second, "newPassword123", "192.0.2.5").andExpect(status().isOk());
		assertInvalid(confirm(second, "anotherPassword123", "192.0.2.5"));
	}

	@Test
	void concurrentConfirmationCanConsumeLinkOnlyOnce(CapturedOutput output) throws Exception {
		User user = saveUser("concurrent-reset@example.com");
		request(user.getEmail(), "192.0.2.14");
		String rawToken = awaitToken(output, 1);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> first = executor.submit(() -> {
				start.await();
				return confirm(rawToken, "newPassword123", "192.0.2.14")
					.andReturn().getResponse().getStatus();
			});
			Future<Integer> second = executor.submit(() -> {
				start.await();
				return confirm(rawToken, "newPassword123", "192.0.2.14")
					.andReturn().getResponse().getStatus();
			});
			start.countDown();
			int firstStatus = first.get(10, TimeUnit.SECONDS);
			int secondStatus = second.get(10, TimeUnit.SECONDS);
			assertThat(firstStatus).isIn(200, 400);
			assertThat(secondStatus).isIn(200, 400);
			assertThat(firstStatus + secondStatus).isEqualTo(600);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void expiredForgedAndUsedTokensReturnIdenticalError(CapturedOutput output)
		throws Exception {
		User user = saveUser("expired-reset@example.com");
		request(user.getEmail(), "192.0.2.6");
		String expired = awaitToken(output, 1);
		PasswordResetToken row = tokens.findByTokenHash(PasswordResetService.hash(expired))
			.orElseThrow();
		ReflectionTestUtils.setField(row, "expiresAt", Instant.now().minusSeconds(1));
		tokens.saveAndFlush(row);
		String expiredResponse = assertInvalid(confirm(expired, "newPassword123", "192.0.2.6"));
		String forgedResponse = assertInvalid(confirm("A".repeat(43),
			"newPassword123", "192.0.2.6"));
		assertThat(objectMapper.readTree(forgedResponse).path("error"))
			.isEqualTo(objectMapper.readTree(expiredResponse).path("error"));
	}

	@Test
	void invalidOrReusedPasswordDoesNotConsumeToken(CapturedOutput output) throws Exception {
		User user = saveUser("policy-reset@example.com");
		request(user.getEmail(), "192.0.2.7");
		String rawToken = awaitToken(output, 1);
		confirm(rawToken, "short", "192.0.2.7")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		confirm(rawToken, "password123", "192.0.2.7")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PASSWORD_REUSE_NOT_ALLOWED"));
		assertThat(tokens.findByTokenHash(PasswordResetService.hash(rawToken))
			.orElseThrow().getUsedAt()).isNull();
		confirm(rawToken, "newPassword123", "192.0.2.7").andExpect(status().isOk());
	}

	@Test
	void requestAndConfirmRateLimitsKeepTheirResponseContracts(CapturedOutput output)
		throws Exception {
		User user = saveUser("limited-reset@example.com");
		for (int index = 0; index < 4; index++) {
			request(user.getEmail(), "192.0.2.8");
		}
		assertThat(tokens.count()).isEqualTo(3);
		assertThat(deliveries.count()).isEqualTo(3);
		assertThat(output).contains("l***@example.com")
			.doesNotContain("Password reset request rate limited email=limited-reset@example.com");

		for (int index = 0; index < 11; index++) {
			User distinct = saveUser("ip-limited-" + index + "@example.com");
			request(distinct.getEmail(), "192.0.2.9");
		}
		assertThat(tokens.count()).isEqualTo(13);
		for (int index = 0; index < 10; index++) {
			assertInvalid(confirm("B".repeat(43), "newPassword123", "192.0.2.10"));
		}
		confirm("B".repeat(43), "newPassword123", "192.0.2.10")
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
	}

	@Test
	void invalidPasswordAttemptsAlsoCountTowardConfirmIpLimit(CapturedOutput output)
		throws Exception {
		User user = saveUser("invalid-attempt-reset@example.com");
		request(user.getEmail(), "192.0.2.15");
		String rawToken = awaitToken(output, 1);
		for (int index = 0; index < 10; index++) {
			confirm(rawToken, "short", "192.0.2.15")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
		confirm(rawToken, "short", "192.0.2.15")
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"));
		assertThat(tokens.findByTokenHash(PasswordResetService.hash(rawToken))
			.orElseThrow().getUsedAt()).isNull();
	}

	@Test
	void auditLogsExcludePlainEmailAndTokenButDevMailShowsLink(CapturedOutput output)
		throws Exception {
		Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(PasswordResetService.class);
		ListAppender<ILoggingEvent> audit = new ListAppender<>();
		audit.start();
		logger.addAppender(audit);
		try {
			User user = saveUser("private-reset@example.com");
			request(user.getEmail(), "192.0.2.11");
			String rawToken = awaitToken(output, 1);
			assertThat(output).contains(rawToken); // LoggingEmailSender is dev-only by design.
			String serviceLogs = audit.list.stream()
				.map(event -> event.getFormattedMessage() + event.getKeyValuePairs())
				.reduce("", String::concat);
			assertThat(serviceLogs)
				.contains("PASSWORD_RESET_REQUESTED")
				.doesNotContain(user.getEmail(), rawToken);
			assertThat(tokens.findAll().getFirst().getTokenHash()).isNotEqualTo(rawToken);
		} finally {
			logger.detachAppender(audit);
			audit.stop();
		}
	}

	@Test
	void googleAccountIsNotGivenLocalResetLink() throws Exception {
		User google = users.saveAndFlush(User.createGoogle(
			"google-reset@example.com", "!google", "학습자", UserRole.LEARNER,
			null, false, null, null, null, "google-reset-subject"
		));
		request(google.getEmail(), "192.0.2.13");
		assertThat(tokens.count()).isZero();
		assertThat(deliveries.count()).isZero();
	}

	@Test
	void requestUsesNginxAppendedIpRatherThanClientPrependedValue() throws Exception {
		User user = saveUser("forwarded-reset@example.com");
		mockMvc.perform(post("/api/auth/password-reset/request")
				.with(request -> { request.setRemoteAddr("172.18.0.2"); return request; })
				.header("X-Forwarded-For", "203.0.113.99, 198.51.100.23")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + user.getEmail() + "\"}"))
			.andExpect(status().isAccepted());
		assertThat(tokens.findAll().getFirst().getRequestedIp())
			.isEqualTo("198.51.100.23");
	}

	@Test
	void cleanupDeletesOnlyTokensExpiredMoreThanSevenDaysAgo() {
		User user = saveUser("cleanup-reset@example.com");
		Instant now = Instant.now();
		PasswordResetToken old = saveToken(user, "a".repeat(64), now.minus(Duration.ofDays(8)));
		PasswordResetToken recent = saveToken(user, "b".repeat(64), now.minus(Duration.ofDays(6)));
		PasswordResetToken valid = saveToken(user, "c".repeat(64), now.plusSeconds(600));

		cleanupScheduler.cleanup();
		assertThat(tokens.findById(old.getId())).isEmpty();
		assertThat(tokens.findById(recent.getId())).isPresent();
		assertThat(tokens.findById(valid.getId())).isPresent();
	}

	private User saveUser(String email) {
		return users.saveAndFlush(User.create(
			email, passwordEncoder.encode("password123"), "학습자"
		));
	}

	private PasswordResetToken saveToken(User user, String hash, Instant expiry) {
		return tokens.saveAndFlush(PasswordResetToken.create(
			user, hash, expiry, "192.0.2.12", Instant.now()
		));
	}

	private MvcResult request(String email, String ip) throws Exception {
		return mockMvc.perform(post("/api/auth/password-reset/request")
				.with(request -> { request.setRemoteAddr(ip); return request; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.message").value(
				"등록된 이메일이면 재설정 안내를 발송했습니다."
			))
			.andReturn();
	}

	private org.springframework.test.web.servlet.ResultActions confirm(
		String token, String password, String ip
	) throws Exception {
		return mockMvc.perform(post("/api/auth/password-reset/confirm")
			.with(request -> { request.setRemoteAddr(ip); return request; })
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\",\"newPassword\":\""
				+ password + "\"}"));
	}

	private String assertInvalid(org.springframework.test.web.servlet.ResultActions result)
		throws Exception {
		return result.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("RESET_TOKEN_INVALID"))
			.andReturn().getResponse().getContentAsString();
	}

	private String awaitToken(CapturedOutput output, int expectedCount) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			Matcher matcher = LINK_TOKEN.matcher(output.getAll());
			Set<String> uniqueTokens = new LinkedHashSet<>();
			while (matcher.find()) {
				uniqueTokens.add(matcher.group(1));
			}
			if (uniqueTokens.size() >= expectedCount) {
				return uniqueTokens.stream().reduce((first, second) -> second).orElseThrow();
			}
			Thread.sleep(20);
		}
		throw new AssertionError("LoggingEmailSender did not emit the expected reset link");
	}

	private String loginJson(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}
}
