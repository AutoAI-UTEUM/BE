package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.logging.AccessLogFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@ActiveProfiles("test")
@io.edupilot.Epic10ServiceMocks
class AuthApiContractTest {

	private static final String TRACE_ID = "auth-contract-trace";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private AccessLogFilter accessLogFilter;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private RefreshTokenService refreshTokenService;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private LearningMaterialRepository learningMaterialRepository;

	@MockitoBean
	private MaterialPageRepository materialPageRepository;

	@MockitoBean
	private LearningSessionRepository learningSessionRepository;

	@MockitoBean
	private ChatMessageRepository chatMessageRepository;

	@MockitoBean
	private NoteRepository noteRepository;

	@MockitoBean
	private FeedbackRepository feedbackRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository quizSubmissionRepository;

	@MockitoBean
	private FileStorage fileStorage;

	private MockMvc mockMvc;
	private User user;

	@BeforeEach
	void setUp() {
		reset(userRepository, refreshTokenRepository);
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter, accessLogFilter)
			.build();
		user = User.create(
			"user@example.com",
			passwordEncoder.encode("password123"),
			"홍길동"
		);
		ReflectionTestUtils.setField(user, "id", 1L);
	}

	@Test
	void signupValidatesEmailAndPasswordContract() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email":"bad-email",
					  "password":"short",
					  "name":"홍길동",
					  "role":"LEARNER"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[*].field").value(
				org.hamcrest.Matchers.hasItems("email", "password")
			));
	}

	@Test
	void signupAcceptsPublicRolesAndRejectsMissingOrReservedRoles() throws Exception {
		when(userRepository.existsByEmail(any())).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 2L);
			return saved;
		});

		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email":"instructor@example.com",
					  "password":"password123",
					  "name":"강사",
					  "role":"INSTRUCTOR"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value(2))
			.andExpect(jsonPath("$.data.role").value("INSTRUCTOR"));

		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email":"missing-role@example.com",
					  "password":"password123",
					  "name":"학습자"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[*].field").value(
				org.hamcrest.Matchers.hasItem("role")
			));

		for (String role : java.util.List.of("USER", "ADMIN", "UNKNOWN")) {
			mockMvc.perform(post("/api/auth/signup")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "email":"invalid-role@example.com",
						  "password":"password123",
						  "name":"사용자",
						  "role":"%s"
						}
						""".formatted(role)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		}
	}

	@Test
	void signupAcceptsOptionalAccountFieldsAndReturnsExpandedUserContract() throws Exception {
		when(userRepository.existsByEmail(any())).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 4L);
			return saved;
		});

		mockMvc.perform(post("/api/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email":"profile@example.com",
					  "password":"password123",
					  "name":"학습자",
					  "role":"LEARNER",
					  "affiliation":"EduPilot University",
					  "learningEmailOptIn":true,
					  "termsVersion":"2026-07-01",
					  "privacyVersion":"2026-07-01"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.userId").value(4))
			.andExpect(jsonPath("$.data.affiliation").value("EduPilot University"))
			.andExpect(jsonPath("$.data.avatarUrl").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.learningEmailOptIn").value(true));
	}

	@Test
	void signupRejectsUnknownOrPartialConsentVersions() throws Exception {
		when(userRepository.existsByEmail(any())).thenReturn(false);

		for (String consentFields : java.util.List.of(
			"\"termsVersion\":\"2026-08-01\",\"privacyVersion\":\"2026-08-01\"",
			"\"termsVersion\":\"2026-07-01\""
		)) {
			mockMvc.perform(post("/api/auth/signup")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{
						  "email":"consent@example.com",
						  "password":"password123",
						  "name":"학습자",
						  "role":"LEARNER",
						  %s
						}
						""".formatted(consentFields)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void emailAvailabilityIsPublicAndUsesSignupValidationRules() throws Exception {
		when(userRepository.existsByEmail("available@example.com")).thenReturn(false);
		when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);
		when(userRepository.existsByEmail("withdrawn@example.com")).thenReturn(false);

		mockMvc.perform(get("/api/auth/email-availability")
				.param("email", "AVAILABLE@Example.COM"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true));

		mockMvc.perform(get("/api/auth/email-availability")
				.param("email", "existing@example.com"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(false));

		mockMvc.perform(get("/api/auth/email-availability")
				.param("email", "withdrawn@example.com"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.available").value(true));

		for (String invalidEmail : java.util.List.of("", "not-an-email")) {
			mockMvc.perform(get("/api/auth/email-availability")
					.param("email", invalidEmail))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}

		mockMvc.perform(get("/api/auth/email-availability"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void loginReturnsAccessAndStrictRefreshCookieWithoutSensitiveBody() throws Exception {
		when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"user@example.com","password":"password123"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").isString())
			.andExpect(jsonPath("$.data.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.data.expiresIn").value(3600))
			.andExpect(jsonPath("$.data.user.id").value(1))
			.andExpect(jsonPath("$.data.user.role").value("LEARNER"))
			.andExpect(jsonPath("$.data.user.affiliation").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.user.avatarUrl").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.user.learningEmailOptIn").value(false))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
				"edupilot_refresh="
			)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
				"SameSite=Lax"
			)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
				"Path=/api/auth"
			)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
				"Max-Age=1209600"
			)))
			.andExpect(content().string(not(containsString("password"))))
			.andExpect(content().string(not(containsString("passwordHash"))))
			.andExpect(content().string(not(containsString("refreshToken"))));
	}

	@Test
	void credentialsAndAuthenticationHeadersNeverAppearInLogs() throws Exception {
		String passwordSentinel = "masking-password-987654";
		String authorizationSentinel = "masking-authorization-token";
		String cookieSentinel = "masking-refresh-cookie";
		when(userRepository.findByEmail("user@example.com"))
			.thenReturn(Optional.of(user));

		Logger rootLogger = (Logger) LoggerFactory.getLogger(
			org.slf4j.Logger.ROOT_LOGGER_NAME
		);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		rootLogger.addAppender(appender);
		try {
			mockMvc.perform(post("/api/auth/login")
					.header(TraceIdFilter.TRACE_ID_HEADER, TRACE_ID)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"email":"user@example.com","password":"%s"}
						""".formatted(passwordSentinel)))
				.andExpect(status().isUnauthorized());

			mockMvc.perform(get("/api/users/me")
					.header(
						HttpHeaders.AUTHORIZATION,
						"Bearer " + authorizationSentinel
					)
					.cookie(new MockCookie(
						"edupilot_refresh",
						cookieSentinel
					)))
				.andExpect(status().isUnauthorized());
		} finally {
			rootLogger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list).isNotEmpty();
		assertThat(appender.list)
			.allSatisfy(event -> assertThat(logText(event))
				.doesNotContain(
					passwordSentinel,
					authorizationSentinel,
					cookieSentinel,
					user.getPasswordHash()
				));
	}

	@Test
	void missingExpiredAndForgedAccessTokensUseStableErrors() throws Exception {
		mockMvc.perform(get("/api/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
			.andExpect(jsonPath("$.traceId").isNotEmpty());

		User expiredUser = User.create("expired@example.com", "hash", "만료");
		ReflectionTestUtils.setField(expiredUser, "id", 2L);
		JwtTokenProvider expiredProvider = new JwtTokenProvider(
			new JwtProperties(
				"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
				java.time.Duration.ofHours(1),
				java.time.Duration.ofDays(14)
			),
			java.time.Clock.fixed(
				Instant.parse("2020-01-01T00:00:00Z"),
				java.time.ZoneOffset.UTC
			)
		);
		String expired = expiredProvider.createAccessToken(expiredUser);

		mockMvc.perform(get("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

		mockMvc.perform(get("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer forged.token.value"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
	}

	@Test
	void meReturnsAuthenticatedActiveUser() throws Exception {
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		String accessToken = jwtTokenProvider.createAccessToken(user);

		mockMvc.perform(get("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.email").value("user@example.com"))
			.andExpect(jsonPath("$.data.role").value("LEARNER"))
			.andExpect(jsonPath("$.data.affiliation").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.avatarUrl").value(
				org.hamcrest.Matchers.nullValue()
			))
			.andExpect(jsonPath("$.data.learningEmailOptIn").value(false));
	}

	@Test
	void profileAndAvatarEndpointsUseExpandedAuthenticatedContract() throws Exception {
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		when(fileStorage.storeAvatar(any(), org.mockito.ArgumentMatchers.eq("png")))
			.thenReturn("avatars/avatar.png");
		String accessToken = jwtTokenProvider.createAccessToken(user);

		mockMvc.perform(patch("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"name":"새 이름","affiliation":"EduPilot University"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.name").value("새 이름"))
			.andExpect(jsonPath("$.data.affiliation").value("EduPilot University"));

		MockMultipartFile avatar = new MockMultipartFile(
			"file",
			"avatar.png",
			"image/png",
			new byte[] {
				(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
				0x00, 0x00, 0x00, 0x00
			}
		);
		mockMvc.perform(multipart("/api/users/me/avatar")
				.file(avatar)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.avatarUrl").value("/api/users/me/avatar"));

		when(fileStorage.load("avatars/avatar.png"))
			.thenReturn(new ByteArrayResource(new byte[] {1, 2, 3}));
		mockMvc.perform(get("/api/users/me/avatar")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL,
				org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void preferencesReturnDefaultsAndPatchSelectedValues() throws Exception {
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		String accessToken = jwtTokenProvider.createAccessToken(user);

		mockMvc.perform(get("/api/users/me/preferences")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.newMaterialNotification").value(true))
			.andExpect(jsonPath("$.data.studyReminder").value(true))
			.andExpect(jsonPath("$.data.aiAnswerStyle").value("NORMAL"));

		mockMvc.perform(patch("/api/users/me/preferences")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"studyReminder":false,"aiAnswerStyle":"DETAILED"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.newMaterialNotification").value(true))
			.andExpect(jsonPath("$.data.studyReminder").value(false))
			.andExpect(jsonPath("$.data.aiAnswerStyle").value("DETAILED"));
	}

	@Test
	void loginAndMePreserveInstructorRole() throws Exception {
		User instructor = User.create(
			"instructor@example.com",
			passwordEncoder.encode("password123"),
			"강사",
			UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 3L);
		when(userRepository.findByEmail("instructor@example.com"))
			.thenReturn(Optional.of(instructor));
		when(userRepository.findById(3L)).thenReturn(Optional.of(instructor));

		var loginResult = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"email":"instructor@example.com","password":"password123"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.user.role").value("INSTRUCTOR"))
			.andReturn();

		String responseBody = loginResult.getResponse().getContentAsString();
		String accessToken = new com.fasterxml.jackson.databind.ObjectMapper()
			.readTree(responseBody)
			.path("data")
			.path("accessToken")
			.asText();

		mockMvc.perform(get("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.role").value("INSTRUCTOR"));
	}

	@Test
	void refreshRotatesCookieAndReturnsOnlyAccessFields() throws Exception {
		String oldRawToken = "old-refresh-token";
		RefreshToken token = new RefreshToken(
			user,
			refreshTokenService.hash(oldRawToken),
			Instant.now().plusSeconds(300)
		);
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(oldRawToken)
		)).thenReturn(Optional.of(token));

		mockMvc.perform(post("/api/auth/refresh")
				.cookie(new MockCookie(RefreshTokenCookie.NAME, oldRawToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.accessToken").isString())
			.andExpect(jsonPath("$.data.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.data.expiresIn").value(3600))
			.andExpect(jsonPath("$.data.user").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
				"edupilot_refresh="
			)))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString(
				"edupilot_refresh=" + oldRawToken
			))));
	}

	@Test
	void refreshWithoutCookieIsInvalidAndLogoutWithoutCookieIsIdempotent() throws Exception {
		mockMvc.perform(post("/api/auth/refresh"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));

		mockMvc.perform(post("/api/auth/logout"))
			.andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString(
				"Max-Age=0"
			)));
	}

	@Test
	void withdrawalRequiresPasswordAndAnonymizesCurrentUser() throws Exception {
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		when(refreshTokenRepository.revokeAllActiveByUserId(any(), any())).thenReturn(1);
		String accessToken = jwtTokenProvider.createAccessToken(user);

		mockMvc.perform(delete("/api/users/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"password":"password123"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(content().string(not(containsString("password"))));

		org.assertj.core.api.Assertions.assertThat(user.getEmail()).isEqualTo("deleted_1");
		org.assertj.core.api.Assertions.assertThat(user.isActive()).isFalse();
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
}
