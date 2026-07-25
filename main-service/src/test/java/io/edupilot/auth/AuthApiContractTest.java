package io.edupilot.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class AuthApiContractTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

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
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository quizSubmissionRepository;

	private MockMvc mockMvc;
	private User user;

	@BeforeEach
	void setUp() {
		reset(userRepository, refreshTokenRepository);
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
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
					{"email":"bad-email","password":"short","name":"홍길동"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[*].field").value(
				org.hamcrest.Matchers.hasItems("email", "password")
			));
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
			.andExpect(jsonPath("$.data.role").value("USER"));
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
}
