package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.edupilot.global.logging.AccessLogFilter;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.policy.PolicyConsentRepository;
import io.edupilot.policy.PolicyDocument;
import io.edupilot.policy.PolicyDocumentRepository;
import io.edupilot.policy.PolicyType;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:google-account-linking;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/google-account-linking",
		"edupilot.admin.infra.enabled=false",
		"edupilot.policy.signup-consent-required=true",
		"logging.level.root=INFO"
	}
)
@ActiveProfiles("jpa-context")
@ExtendWith(OutputCaptureExtension.class)
class GoogleAccountLinkingIntegrationTest {

	private static final String PASSWORD = "chosen-password-455";
	private static final String ID_TOKEN = "synthetic-google-token-455";
	private static final String SUBJECT = "synthetic-google-subject-455";
	private static final String EMAIL = "pre-hijack-455@example.com";
	private static final List<Map<String, String>> CONSENTS = List.of(
		Map.of("type", "TERMS", "version", "0.9"),
		Map.of("type", "PRIVACY", "version", "0.9")
	);

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private AccessLogFilter accessLogFilter;
	@Autowired private UserRepository users;
	@Autowired private PolicyDocumentRepository policies;
	@Autowired private PolicyConsentRepository consents;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private AuthSessionRepository sessions;
	@MockitoBean private GoogleIdTokenVerifier verifier;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		consents.deleteAll();
		refreshTokens.deleteAll();
		sessions.deleteAll();
		users.deleteAll();
		policies.deleteAll();
		for (PolicyType type : PolicyType.values()) {
			policies.saveAndFlush(PolicyDocument.create(
				type, "0.9", type.name(), "synthetic policy", null, true,
				Instant.EPOCH, 0L, Instant.EPOCH));
		}
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter, accessLogFilter)
			.build();
	}

	@Test
	void preRegisteredLocalAccountCannotBeHijackedThroughGoogleEmail(CapturedOutput output)
		throws Exception {
		MvcResult signup = postJson("/api/auth/signup", signupBody(EMAIL));
		assertThat(signup.getResponse().getStatus()).isEqualTo(200);
		long localId = json(signup).path("data").path("userId").asLong();
		User original = users.findById(localId).orElseThrow();
		MvcResult before = passwordLogin(EMAIL);
		long sessionsBefore = sessions.count();
		long refreshBefore = refreshTokens.count();
		when(verifier.verify(ID_TOKEN)).thenReturn(new GoogleProfile(
			SUBJECT, "PRE-HIJACK-455@Example.com", "Google email owner"));

		MvcResult google = postJson("/api/auth/google", Map.of("idToken", ID_TOKEN));
		long sessionsAfterGoogle = sessions.count();
		long refreshAfterGoogle = refreshTokens.count();
		User afterGoogle = users.findById(localId).orElseThrow();
		MvcResult after = passwordLogin(EMAIL);
		assertThat(json(before).path("data").path("user").path("id").asLong()).isEqualTo(localId);
		assertThat(json(after).path("data").path("user").path("id").asLong()).isEqualTo(localId);
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + json(before).path("data").path("accessToken").asText()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/refresh")
				.cookie(before.getResponse().getCookie(RefreshTokenCookie.NAME)))
			.andExpect(status().isOk());

		JsonNode body = json(google);
		assertSoftly(softly -> {
			softly.assertThat(google.getResponse().getStatus()).isEqualTo(409);
			softly.assertThat(body.path("error").path("code").asText())
				.isEqualTo("EMAIL_ALREADY_EXISTS");
			softly.assertThat(body.has("data")).isFalse();
			softly.assertThat(google.getResponse().containsHeader(HttpHeaders.SET_COOKIE)).isFalse();
			softly.assertThat(afterGoogle.getGoogleSub()).isNull();
			softly.assertThat(afterGoogle.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
			softly.assertThat(afterGoogle.getPasswordHash()).isEqualTo(original.getPasswordHash());
			softly.assertThat(afterGoogle.getName()).isEqualTo(original.getName());
			softly.assertThat(ReflectionTestUtils.getField(afterGoogle, "updatedAt"))
				.isEqualTo(ReflectionTestUtils.getField(original, "updatedAt"));
			softly.assertThat(sessionsAfterGoogle).isEqualTo(sessionsBefore);
			softly.assertThat(refreshAfterGoogle).isEqualTo(refreshBefore);
		});
		assertThat(consents.count()).isEqualTo(2);
		assertThat(google.getResponse().getContentAsString()).doesNotContain(
			ID_TOKEN, SUBJECT, EMAIL, PASSWORD, "accessToken", "refreshToken", "passwordHash");
		assertThat(output.getAll()).doesNotContain(ID_TOKEN, SUBJECT, EMAIL, PASSWORD,
			original.getPasswordHash(), json(before).path("data").path("accessToken").asText());
	}

	@Test
	void newGoogleSignupAndSameSubjectLoginPreserveConsentAndUserIdentity() throws Exception {
		when(verifier.verify(ID_TOKEN)).thenReturn(new GoogleProfile(SUBJECT, EMAIL, "Google user"));
		MvcResult created = postJson("/api/auth/google", googleBody(ID_TOKEN));
		assertThat(created.getResponse().getStatus()).isEqualTo(200);
		MvcResult retried = postJson("/api/auth/google", Map.of("idToken", ID_TOKEN));
		assertThat(retried.getResponse().getStatus()).isEqualTo(200);
		assertThat(json(created).path("data").path("user").path("id"))
			.isEqualTo(json(retried).path("data").path("user").path("id"));
		assertThat(json(retried).path("data").path("pendingConsents").size()).isZero();
		assertThat(users.count()).isEqualTo(1);
		assertThat(consents.count()).isEqualTo(2);
		assertThat(users.findByGoogleSub(SUBJECT).orElseThrow().getAuthProvider())
			.isEqualTo(AuthProvider.GOOGLE);
		MvcResult password = postJson("/api/auth/login", Map.of("email", EMAIL, "password", PASSWORD));
		assertThat(password.getResponse().getStatus()).isEqualTo(401);
		assertThat(json(password).path("error").path("code").asText())
			.isEqualTo("INVALID_CREDENTIALS");
	}

	@Test
	void concurrentDifferentGoogleSubjectsSharingEmailNeverMerge() throws Exception {
		when(verifier.verify("first-synthetic-token"))
			.thenReturn(new GoogleProfile("first-subject", EMAIL, "First"));
		when(verifier.verify("second-synthetic-token"))
			.thenReturn(new GoogleProfile("second-subject", EMAIL, "Second"));

		List<MvcResult> results = concurrentPosts("/api/auth/google", googleBody("first-synthetic-token"),
			"/api/auth/google", googleBody("second-synthetic-token"));
		assertThat(results).extracting(result -> result.getResponse().getStatus())
			.containsExactlyInAnyOrder(200, 409);
		User saved = users.findByEmail(EMAIL).orElseThrow();
		String successfulSubject = results.getFirst().getResponse().getStatus() == 200
			? "first-subject" : "second-subject";
		assertThat(saved.getGoogleSub()).isEqualTo(successfulSubject);
		assertThat(saved.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
		assertSingleCommittedSignup(results);
	}

	@Test
	void concurrentSameSubjectSignupPreservesUniqueSubjectAndSingleIdentity() throws Exception {
		when(verifier.verify("first-synthetic-token"))
			.thenReturn(new GoogleProfile(SUBJECT, EMAIL, "First"));
		when(verifier.verify("second-synthetic-token"))
			.thenReturn(new GoogleProfile(SUBJECT, "second-455@example.com", "Second"));

		List<MvcResult> results = concurrentPosts("/api/auth/google", googleBody("first-synthetic-token"),
			"/api/auth/google", googleBody("second-synthetic-token"));
		User saved = users.findByGoogleSub(SUBJECT).orElseThrow();
		long successfulLogins = 0;
		for (MvcResult result : results) {
			assertThat(result.getResponse().getStatus()).isIn(200, 409);
			if (result.getResponse().getStatus() == 200) {
				successfulLogins++;
				assertThat(json(result).path("data").path("user").path("id").asLong())
					.isEqualTo(saved.getId());
			}
		}
		assertThat(successfulLogins).isPositive();
		assertThat(users.count()).isEqualTo(1);
		assertThat(consents.count()).isEqualTo(2);
		assertThat(sessions.count()).isEqualTo(successfulLogins);
		assertThat(refreshTokens.count()).isEqualTo(successfulLogins);
		assertCollisionHasNoTokens(results);
	}

	@Test
	void concurrentLocalAndGoogleSignupNeverAttachGoogleToLocalAccount() throws Exception {
		when(verifier.verify(ID_TOKEN)).thenReturn(new GoogleProfile(SUBJECT, EMAIL, "Google user"));
		List<MvcResult> results = concurrentPosts("/api/auth/signup", signupBody(EMAIL),
			"/api/auth/google", googleBody(ID_TOKEN));
		assertThat(results).extracting(result -> result.getResponse().getStatus())
			.containsExactlyInAnyOrder(200, 409);
		User saved = users.findByEmail(EMAIL).orElseThrow();
		if (results.getFirst().getResponse().getStatus() == 200) {
			assertThat(saved.getAuthProvider()).isEqualTo(AuthProvider.LOCAL);
			assertThat(saved.getGoogleSub()).isNull();
			assertThat(sessions.count()).isZero();
			assertThat(refreshTokens.count()).isZero();
		} else {
			assertThat(saved.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
			assertThat(saved.getGoogleSub()).isEqualTo(SUBJECT);
			assertThat(sessions.count()).isEqualTo(1);
			assertThat(refreshTokens.count()).isEqualTo(1);
		}
		assertThat(users.count()).isEqualTo(1);
		assertThat(consents.count()).isEqualTo(2);
		assertCollisionHasNoTokens(results);
	}

	private void assertSingleCommittedSignup(List<MvcResult> results) throws Exception {
		assertThat(users.count()).isEqualTo(1);
		assertThat(consents.count()).isEqualTo(2);
		assertThat(sessions.count()).isEqualTo(1);
		assertThat(refreshTokens.count()).isEqualTo(1);
		assertCollisionHasNoTokens(results);
	}

	private void assertCollisionHasNoTokens(List<MvcResult> results) throws Exception {
		for (MvcResult result : results) {
			if (result.getResponse().getStatus() == 409) {
				assertThat(json(result).path("error").path("code").asText())
					.isEqualTo("EMAIL_ALREADY_EXISTS");
				assertThat(json(result).has("data")).isFalse();
				assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
			}
		}
	}

	private List<MvcResult> concurrentPosts(String firstPath, Object firstBody,
		String secondPath, Object secondBody) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<MvcResult> first = executor.submit(() -> {
				start.await();
				return postJson(firstPath, firstBody);
			});
			Future<MvcResult> second = executor.submit(() -> {
				start.await();
				return postJson(secondPath, secondBody);
			});
			start.countDown();
			return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
		}
	}

	private MvcResult passwordLogin(String email) throws Exception {
		MvcResult result = postJson("/api/auth/login", Map.of("email", email, "password", PASSWORD));
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		return result;
	}

	private Map<String, Object> signupBody(String email) {
		return Map.of("email", email, "password", PASSWORD, "name", "Local user",
			"role", "LEARNER", "consents", CONSENTS);
	}

	private Map<String, Object> googleBody(String token) {
		return Map.of("idToken", token, "role", "LEARNER", "consents", CONSENTS);
	}

	private MvcResult postJson(String path, Object body) throws Exception {
		return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body))).andReturn();
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}
}
