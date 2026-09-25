package io.edupilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.auth.AuthSessionRepository;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:admin-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
		"edupilot.storage.root-directory=build/test-storage/admin-api"
	}
)
@ActiveProfiles("jpa-context")
@Import(AdminApiIntegrationTest.FixedAdminAiUsageServiceConfiguration.class)
class AdminApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private RefreshTokenService refreshTokenService;
	@Autowired private RefreshTokenRepository refreshTokenRepository;
	@Autowired private AuthSessionRepository authSessionRepository;
	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private AiUsageLogRepository usageLogRepository;
	@Autowired private AdminClassroomService adminClassroomService;
	@Autowired private AdminUserService adminUserService;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private MockMvc mockMvc;
	private User admin;
	private User instructor;
	private User learner;
	private User deletedUser;
	private Classroom firstClassroom;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("""
			create alias if not exists convert_tz
			for 'io.edupilot.admin.H2TimeZoneFunctions.convertTz'
			""");
		usageLogRepository.deleteAll();
		memberRepository.deleteAll();
		classroomRepository.deleteAll();
		refreshTokenRepository.deleteAll();
		authSessionRepository.deleteAll();
		userRepository.deleteAll();

		admin = saveUser("admin@example.com", "관리자", UserRole.ADMIN);
		instructor = saveUser(
			"instructor@example.com",
			"강사",
			UserRole.INSTRUCTOR
		);
		learner = saveUser("learner@example.com", "학습자", UserRole.LEARNER);
		deletedUser = saveUser(
			"withdrawn@example.com",
			"탈퇴 예정",
			UserRole.LEARNER
		);
		deletedUser.withdraw();
		userRepository.saveAndFlush(deletedUser);

		firstClassroom = saveClassroom("가 강의실", "admin-room-1");
		Classroom second = saveClassroom("나 강의실", "admin-room-2");
		saveClassroom("다 강의실", "admin-room-3");
		saveClassroom("라 강의실", "admin-room-4");
		memberRepository.saveAndFlush(ClassroomMember.create(
			firstClassroom,
			learner,
			Instant.parse("2026-08-20T00:00:00Z")
		));
		memberRepository.saveAndFlush(ClassroomMember.create(
			second,
			learner,
			Instant.parse("2026-08-21T00:00:00Z")
		));
		memberRepository.saveAndFlush(ClassroomMember.create(
			second,
			deletedUser,
			Instant.parse("2026-08-22T00:00:00Z")
		));

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
	}

	@Test
	void passwordResetEndpointRejectsLearnerAndInstructorTokens() throws Exception {
		String endpoint = "/api/admin/users/" + learner.getId() + "/password-reset";

		mockMvc.perform(post(endpoint)
				.header(HttpHeaders.AUTHORIZATION, bearer(learner)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
		mockMvc.perform(post(endpoint)
				.header(HttpHeaders.AUTHORIZATION, bearer(instructor)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void adminPasswordResetReturnsPasswordOnceRevokesTokensAndAuditsWithoutSecret()
		throws Exception {
		User target = userRepository.saveAndFlush(User.create(
			"reset-target@example.com",
			passwordEncoder.encode("oldPassword123"),
			"초기화 대상",
			UserRole.LEARNER
		));
		refreshTokenService.issue(target);
		refreshTokenService.issue(target);
		Logger logger = (Logger)LoggerFactory.getLogger(AdminAuditInterceptor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		String responseBody;
		try {
			responseBody = mockMvc.perform(post(
					"/api/admin/users/" + target.getId() + "/password-reset"
				)
					.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk())
				.andExpect(header().string(
					HttpHeaders.CACHE_CONTROL,
					org.hamcrest.Matchers.containsString("no-store")
				))
				.andExpect(jsonPath("$.data.temporaryPassword").isString())
				.andExpect(jsonPath("$.data.message").value(
					"로그인 후 즉시 변경 안내"
				))
				.andReturn().getResponse().getContentAsString();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		String temporaryPassword = objectMapper.readTree(responseBody)
			.path("data")
			.path("temporaryPassword")
			.asText();
		User resetTarget = userRepository.findById(target.getId()).orElseThrow();
		assertThat(temporaryPassword).hasSize(16);
		assertThat(resetTarget.getPasswordHash()).isNotEqualTo(temporaryPassword);
		assertThat(passwordEncoder.matches(
			temporaryPassword,
			resetTarget.getPasswordHash()
		)).isTrue();
		assertThat(refreshTokenRepository.findAll())
			.hasSize(2)
			.allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
		assertThat(authSessionRepository.findAll())
			.hasSize(2)
			.allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());
		assertThat(appender.list)
			.anySatisfy(event -> assertThat(logText(event))
				.contains(
					"actorUserId=\"" + admin.getId() + "\"",
					"targetType=\"USER\"",
					"targetId=\"" + target.getId() + "\"",
					"action=\"ADMIN_PASSWORD_RESET\"",
					"occurredAt=\""
				))
			.allSatisfy(event -> assertThat(logText(event))
				.doesNotContain(temporaryPassword));

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email":"reset-target@example.com",
					  "password":"%s"
					}
					""".formatted(temporaryPassword)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.user.id").value(target.getId()));
	}

	@Test
	void accountManagementEndpointsRejectNonAdminsAndInvalidSuspendReason() throws Exception {
		for (User nonAdmin : new User[] {learner, instructor}) {
			mockMvc.perform(post("/api/admin/users/" + learner.getId() + "/suspend")
					.header(HttpHeaders.AUTHORIZATION, bearer(nonAdmin))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"reason\":\"운영 확인\"}"))
				.andExpect(status().isForbidden());
			mockMvc.perform(post("/api/admin/users/" + learner.getId() + "/reinstate")
					.header(HttpHeaders.AUTHORIZATION, bearer(nonAdmin)))
				.andExpect(status().isForbidden());
			mockMvc.perform(patch("/api/admin/users/" + learner.getId() + "/role")
					.header(HttpHeaders.AUTHORIZATION, bearer(nonAdmin))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"role\":\"ADMIN\"}"))
				.andExpect(status().isForbidden());
		}
		mockMvc.perform(post("/api/admin/users/" + learner.getId() + "/suspend")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void suspendAndReinstateRevokesSessionsAndBlocksExistingAccessToken() throws Exception {
		User target = userRepository.saveAndFlush(User.create(
			"suspend-target@example.com",
			passwordEncoder.encode("password123"),
			"정지 대상",
			UserRole.LEARNER
		));
		String accessToken = bearer(target);
		String refreshToken = refreshTokenService.issue(target).rawToken();

		mockMvc.perform(post("/api/admin/users/" + target.getId() + "/suspend")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"운영 정책 위반\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("SUSPENDED"))
			.andExpect(jsonPath("$.data.suspendedReason").value("운영 정책 위반"))
			.andExpect(jsonPath("$.data.suspendedAt").isString());
		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("status", "SUSPENDED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].id").value(target.getId()))
			.andExpect(jsonPath("$.data.items[0].suspendedReason").value("운영 정책 위반"));
		assertThat(refreshTokenRepository.findAll())
			.allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
		assertThat(authSessionRepository.findAll())
			.allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, accessToken))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
		mockMvc.perform(post("/api/auth/refresh")
				.cookie(new Cookie("edupilot_refresh", refreshToken)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"suspend-target@example.com\",\"password\":\"wrong123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"suspend-target@example.com\",\"password\":\"password123\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("ACCOUNT_SUSPENDED"));

		mockMvc.perform(post("/api/admin/users/" + target.getId() + "/reinstate")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.suspendedReason").value(org.hamcrest.Matchers.nullValue()));
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"suspend-target@example.com\",\"password\":\"password123\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void roleChangeRevokesSessionsAndOldAuthorityAndProtectsLastAdmin() throws Exception {
		String oldToken = bearer(instructor);
		refreshTokenService.issue(instructor);
		Logger logger = (Logger)LoggerFactory.getLogger(AdminAuditInterceptor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			mockMvc.perform(patch("/api/admin/users/" + instructor.getId() + "/role")
					.header(HttpHeaders.AUTHORIZATION, bearer(admin))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"role\":\"LEARNER\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.role").value("LEARNER"));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		assertThat(appender.list).hasSize(1);
		assertThat(logText(appender.list.getFirst()))
			.contains("action=\"USER_ROLE_CHANGED\"", "before=\"INSTRUCTOR\"", "after=\"LEARNER\"");
		assertThat(authSessionRepository.findAll())
			.allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, oldToken))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));

		mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/suspend")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"self\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("ADMIN_SELF_MODIFICATION"));
		mockMvc.perform(patch("/api/admin/users/" + admin.getId() + "/role")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"LEARNER\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("ADMIN_SELF_MODIFICATION"));
	}

	@Test
	void loginAndRefreshRateLimitsExposeRetryAfterWithoutLeakingAccountExistence()
		throws Exception {
		User target = userRepository.saveAndFlush(User.create(
			"limited@example.com", passwordEncoder.encode("password123"), "제한 대상"
		));
		String loginBody = "{\"email\":\"limited@example.com\",\"password\":\"wrong123\"}";
		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/api/auth/login")
					.header("X-Forwarded-For", "192.0.2.40")
					.contentType(MediaType.APPLICATION_JSON).content(loginBody))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
		}
		mockMvc.perform(post("/api/auth/login")
				.header("X-Forwarded-For", "192.0.2.40")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"limited@example.com\",\"password\":\"password123\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("LOGIN_RATE_LIMITED"))
			.andExpect(header().string(HttpHeaders.RETRY_AFTER,
				org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));

		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/api/auth/refresh")
					.header("X-Forwarded-For", "192.0.2.41"))
				.andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/auth/refresh")
				.header("X-Forwarded-For", "192.0.2.41"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string(HttpHeaders.RETRY_AFTER,
				org.hamcrest.Matchers.matchesPattern("[1-9][0-9]*")));
	}

	@Test
	void concurrentDemotionsLeaveAtLeastOneActiveAdmin() throws Exception {
		User secondAdmin = saveUser("second-admin@example.com", "두 번째 관리자", UserRole.ADMIN);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> demoteAfterBarrier(
				admin.getId(), secondAdmin.getId(), ready, start
			));
			var second = executor.submit(() -> demoteAfterBarrier(
				secondAdmin.getId(), admin.getId(), ready, start
			));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder("OK", "LAST_ADMIN_PROTECTED");
			assertThat(userRepository.findAll().stream()
				.filter(user -> user.getRole() == UserRole.ADMIN && user.isActive()).toList())
				.hasSize(1);
		} finally {
			executor.shutdownNow();
		}
	}

	private String demoteAfterBarrier(
		Long actorId,
		Long targetId,
		CountDownLatch ready,
		CountDownLatch start
	) throws InterruptedException {
		ready.countDown();
		start.await();
		try {
			adminUserService.changeRole(actorId, targetId, UserRole.LEARNER);
			return "OK";
		} catch (BusinessException exception) {
			return exception.errorCode().code();
		}
	}

	@Test
	void adminPasswordResetRejectsGoogleDeletedAndSelfTargets() throws Exception {
		User googleTarget = userRepository.saveAndFlush(User.createGoogle(
			"google-target@example.com",
			"!google-account",
			"구글 대상",
			UserRole.LEARNER,
			null,
			false,
			null,
			null,
			null,
			"google-target-sub"
		));
		assertThat(googleTarget.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);

		mockMvc.perform(post(
				"/api/admin/users/" + googleTarget.getId() + "/password-reset"
			)
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PASSWORD_NOT_SUPPORTED"));

		mockMvc.perform(post(
				"/api/admin/users/" + deletedUser.getId() + "/password-reset"
			)
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_NOT_ALLOWED"));

		mockMvc.perform(post(
				"/api/admin/users/" + admin.getId() + "/password-reset"
			)
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_NOT_ALLOWED"));

		mockMvc.perform(post("/api/admin/users/999999/password-reset")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
	}

	@Test
	void enforcesAdminAccessForEveryReadEndpoint() throws Exception {
		List<String> endpoints = List.of(
			"/api/admin/users",
			"/api/admin/users/" + learner.getId(),
			"/api/admin/classrooms",
			"/api/admin/classrooms/" + firstClassroom.getId(),
			"/api/admin/ai-usage/summary?from=2026-08-23&to=2026-08-29",
			"/api/admin/ai-usage/users?from=2026-08-23&to=2026-08-29"
		);

		for (String endpoint : endpoints) {
			mockMvc.perform(get(endpoint))
				.andExpect(status().isUnauthorized());
			mockMvc.perform(get(endpoint)
					.header(HttpHeaders.AUTHORIZATION, bearer(learner)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(endpoint)
					.header(HttpHeaders.AUTHORIZATION, bearer(instructor)))
				.andExpect(status().isForbidden());
			mockMvc.perform(get(endpoint)
					.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
				.andExpect(status().isOk());
		}
	}

	@Test
	void listsSearchesAndFiltersUsersIncludingDeletedAccounts() throws Exception {
		User googleUser = userRepository.saveAndFlush(User.createGoogle(
			"case.match@example.com",
			"password-hash",
			"Search Person",
			UserRole.INSTRUCTOR,
			"EduPilot",
			true,
			"terms-v1",
			"privacy-v1",
			Instant.parse("2026-08-01T00:00:00Z"),
			"private-google-sub"
		));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("page", "0")
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(2))
			.andExpect(jsonPath("$.data.totalElements").value(5));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("q", "CASE.MATCH"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.items[0].id").value(googleUser.getId()));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("q", "search person"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.items[0].email")
				.value("case.match@example.com"));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("role", "INSTRUCTOR")
				.param("status", "ACTIVE"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("status", "DELETED"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.items[0].id").value(deletedUser.getId()))
			.andExpect(jsonPath("$.data.items[0].status").value("DELETED"));
	}

	@Test
	void userResponsesCannotSerializeCredentialFields() throws Exception {
		User googleUser = userRepository.saveAndFlush(User.createGoogle(
			"secret@example.com",
			"private-password-hash",
			"민감정보 검증",
			UserRole.LEARNER,
			"EduPilot",
			false,
			"terms-v1",
			"privacy-v1",
			Instant.parse("2026-08-01T00:00:00Z"),
			"private-google-sub"
		));

		String listBody = mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String detailBody = mockMvc.perform(get(
				"/api/admin/users/" + googleUser.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.affiliation").value("EduPilot"))
			.andExpect(jsonPath("$.data.consentedAt").isString())
			.andReturn().getResponse().getContentAsString();

		assertNoCredentialKeys(listBody);
		assertNoCredentialKeys(detailBody);
	}

	@Test
	void returnsClassroomMemberCountsAndDetailsWithoutNPlusOne() throws Exception {
		mockMvc.perform(get("/api/admin/classrooms")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("sort", "NAME"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].id")
				.value(firstClassroom.getId()))
			.andExpect(jsonPath("$.data.items[0].memberCount").value(1))
			.andExpect(jsonPath("$.data.items[1].memberCount").value(2));

		mockMvc.perform(get("/api/admin/classrooms/" + firstClassroom.getId())
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.instructor.id").value(instructor.getId()))
			.andExpect(jsonPath("$.data.memberCount").value(1))
			.andExpect(jsonPath("$.data.members[0].userId").value(learner.getId()))
			.andExpect(jsonPath("$.data.members[0].role").value("LEARNER"))
			.andExpect(jsonPath("$.data.members[0].joinedAt").isString());

		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class)
			.getStatistics();
		statistics.clear();
		adminClassroomService.list(AdminListSort.RECENT, 0, 1);
		long oneItemQueryCount = statistics.getPrepareStatementCount();

		entityManager.clear();
		statistics.clear();
		adminClassroomService.list(AdminListSort.RECENT, 0, 3);
		long threeItemQueryCount = statistics.getPrepareStatementCount();

		assertThat(oneItemQueryCount).isEqualTo(threeItemQueryCount);
		assertThat(threeItemQueryCount).isLessThanOrEqualTo(3);
	}

	@Test
	void aggregatesDailyAndFeatureUsageAcrossKstMidnight() throws Exception {
		insertUsage(
			learner.getId(),
			AiFeature.DOC_CHAT,
			true,
			10L,
			20L,
			null,
			Instant.parse("2026-08-25T14:59:59Z")
		);
		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			false,
			null,
			null,
			null,
			Instant.parse("2026-08-25T15:00:00Z")
		);
		insertUsage(
			instructor.getId(),
			AiFeature.TURN,
			true,
			5L,
			null,
			2L,
			Instant.parse("2026-08-26T01:00:00Z")
		);

		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-08-25")
				.param("to", "2026-08-26"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.daily.length()").value(2))
			.andExpect(jsonPath("$.data.daily[0].date").value("2026-08-25"))
			.andExpect(jsonPath("$.data.daily[0].callCount").value(1))
			.andExpect(jsonPath("$.data.daily[0].successCount").value(1))
			.andExpect(jsonPath("$.data.daily[0].failCount").value(0))
			.andExpect(jsonPath("$.data.daily[0].inputTokens").value(10))
			.andExpect(jsonPath("$.data.daily[0].outputTokens").value(20))
			.andExpect(jsonPath("$.data.daily[0].reasoningTokens").isEmpty())
			.andExpect(jsonPath("$.data.daily[1].date").value("2026-08-26"))
			.andExpect(jsonPath("$.data.daily[1].callCount").value(2))
			.andExpect(jsonPath("$.data.daily[1].successCount").value(1))
			.andExpect(jsonPath("$.data.daily[1].failCount").value(1))
			.andExpect(jsonPath("$.data.daily[1].inputTokens").value(5))
			.andExpect(jsonPath("$.data.daily[1].outputTokens").isEmpty())
			.andExpect(jsonPath("$.data.daily[1].reasoningTokens").value(2))
			.andExpect(jsonPath("$.data.features[0].feature").value("DOC_CHAT"))
			.andExpect(jsonPath("$.data.features[0].callCount").value(1))
			.andExpect(jsonPath("$.data.features[1].feature").value("TURN"))
			.andExpect(jsonPath("$.data.features[1].callCount").value(2));
	}

	@Test
	void returnsTopUsersIncludingDeletedAccountsAndHonorsLimit() throws Exception {
		for (int index = 0; index < 4; index++) {
			insertUsage(
				deletedUser.getId(),
				AiFeature.TURN,
				true,
				1L,
				2L,
				null,
				Instant.parse("2026-08-25T00:00:00Z").plusSeconds(index)
			);
		}
		for (int index = 0; index < 3; index++) {
			insertUsage(
				instructor.getId(),
				AiFeature.DOC_CHAT,
				true,
				3L,
				4L,
				1L,
				Instant.parse("2026-08-25T01:00:00Z").plusSeconds(index)
			);
		}
		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			false,
			null,
			null,
			null,
			Instant.parse("2026-08-25T02:00:00Z")
		);

		mockMvc.perform(get("/api/admin/ai-usage/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-08-25")
				.param("to", "2026-08-25")
				.param("limit", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items.length()").value(2))
			.andExpect(jsonPath("$.data.items[0].userId")
				.value(deletedUser.getId()))
			.andExpect(jsonPath("$.data.items[0].status").value("DELETED"))
			.andExpect(jsonPath("$.data.items[0].callCount").value(4))
			.andExpect(jsonPath("$.data.items[0].inputTokens").value(4))
			.andExpect(jsonPath("$.data.items[1].userId")
				.value(instructor.getId()))
			.andExpect(jsonPath("$.data.items[1].callCount").value(3));
	}

	@Test
	void validatesDateRangeAndUsesRecentSevenKstDaysByDefault() throws Exception {
		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-08-30")
				.param("to", "2026-08-29"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("from", "2026-01-01")
				.param("to", "2026-04-03"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		mockMvc.perform(get("/api/admin/ai-usage/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("limit", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			true,
			1L,
			1L,
			1L,
			Instant.parse("2026-08-22T14:59:59Z")
		);
		insertUsage(
			learner.getId(),
			AiFeature.TURN,
			true,
			2L,
			2L,
			2L,
			Instant.parse("2026-08-22T15:00:00Z")
		);

		mockMvc.perform(get("/api/admin/ai-usage/summary")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.daily.length()").value(1))
			.andExpect(jsonPath("$.data.daily[0].date").value("2026-08-23"))
			.andExpect(jsonPath("$.data.daily[0].callCount").value(1))
			.andExpect(jsonPath("$.data.daily[0].inputTokens").value(2));
	}

	private User saveUser(String email, String name, UserRole role) {
		return userRepository.saveAndFlush(User.create(
			email,
			"password-hash",
			name,
			role
		));
	}

	private Classroom saveClassroom(String name, String inviteCode) {
		return classroomRepository.saveAndFlush(Classroom.create(
			instructor,
			name,
			LocalDate.of(2026, 8, 1),
			LocalDate.of(2026, 12, 31),
			ClassroomColor.BLUE,
			"관리자 조회 테스트",
			inviteCode
		));
	}

	private void insertUsage(
		Long userId,
		AiFeature feature,
		boolean success,
		Long inputTokens,
		Long outputTokens,
		Long reasoningTokens,
		Instant createdAt
	) {
		jdbcTemplate.update("""
			insert into ai_usage_log (
				user_id,
				feature,
				model,
				input_tokens,
				output_tokens,
				reasoning_tokens,
				success,
				created_at
			) values (?, ?, ?, ?, ?, ?, ?, ?)
			""",
			userId,
			feature.name(),
			"test-model",
			inputTokens,
			outputTokens,
			reasoningTokens,
			success,
			Timestamp.valueOf(LocalDateTime.ofInstant(
				createdAt,
				ZoneOffset.UTC
			))
		);
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}

	@Test
	void userListReturnsLastActiveAtAndNullForNoActivity() throws Exception {
		Instant lastActiveAt = Instant.parse("2026-09-01T02:03:04Z");
		userRepository.updateLastActiveAt(instructor.getId(), lastActiveAt);
		entityManager.clear();

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("q", "instructor@example.com"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].lastActiveAt")
				.value("2026-09-01T02:03:04Z"));

		mockMvc.perform(get("/api/admin/users")
				.header(HttpHeaders.AUTHORIZATION, bearer(admin))
				.param("q", "learner@example.com"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].lastActiveAt")
				.value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void sortsUsersByRecentActivityWithNullsLastInBothDirections() {
		userRepository.updateLastActiveAt(
			learner.getId(),
			Instant.parse("2026-09-01T00:00:00Z")
		);
		userRepository.updateLastActiveAt(
			instructor.getId(),
			Instant.parse("2026-09-02T00:00:00Z")
		);
		entityManager.clear();

		var descending = adminUserService.list(
			null,
			null,
			null,
			AdminUserSort.RECENT_ACTIVITY_DESC,
			0,
			10
		);
		var ascending = adminUserService.list(
			null,
			null,
			null,
			AdminUserSort.RECENT_ACTIVITY_ASC,
			0,
			10
		);

		assertThat(descending.items()).extracting(item -> item.id())
			.containsExactly(
				instructor.getId(),
				learner.getId(),
				deletedUser.getId(),
				admin.getId()
			);
		assertThat(ascending.items()).extracting(item -> item.id())
			.containsExactly(
				learner.getId(),
				instructor.getId(),
				admin.getId(),
				deletedUser.getId()
			);
	}

	@Test
	void recentUserSortRemainsBasedOnCreatedAt() {
		userRepository.updateLastActiveAt(
			instructor.getId(),
			Instant.parse("2099-01-01T00:00:00Z")
		);
		jdbcTemplate.update(
			"update users set created_at = ? where id = ?",
			Timestamp.valueOf("2098-01-01 00:00:00"),
			learner.getId()
		);
		entityManager.clear();

		var response = adminUserService.list(
			null,
			null,
			null,
			AdminUserSort.RECENT,
			0,
			10
		);

		assertThat(response.items().get(0).id()).isEqualTo(learner.getId());
	}

	private String logText(ILoggingEvent event) {
		String throwableMessage = event.getThrowableProxy() == null
			? ""
			: String.valueOf(event.getThrowableProxy().getMessage());
		return event.getFormattedMessage()
			+ Arrays.toString(event.getArgumentArray())
			+ event.getKeyValuePairs()
			+ event.getMDCPropertyMap()
			+ throwableMessage;
	}

	private void assertNoCredentialKeys(String body) {
		assertThat(body)
			.doesNotContain(
				"passwordHash",
				"password_hash",
				"googleSub",
				"google_sub",
				"refreshToken",
				"refresh_token"
			);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedAdminAiUsageServiceConfiguration {

		@Bean
		@Primary
		AdminAiUsageService fixedAdminAiUsageService(
			AiUsageLogRepository usageLogRepository
		) {
			return new AdminAiUsageService(
				usageLogRepository,
				Clock.fixed(NOW, ZoneOffset.UTC)
			);
		}
	}
}
