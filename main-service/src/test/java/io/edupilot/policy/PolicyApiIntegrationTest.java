package io.edupilot.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.auth.AuthSessionRepository;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:policy-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/policy-api"
})
@ActiveProfiles("jpa-context")
class PolicyApiIntegrationTest {
	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokens;
	@Autowired private PasswordEncoder passwordEncoder;
	@Autowired private UserRepository users;
	@Autowired private RefreshTokenRepository refreshTokens;
	@Autowired private AuthSessionRepository authSessions;
	@Autowired private PolicyDocumentRepository documents;
	@Autowired private PolicyConsentRepository consents;
	private MockMvc mvc;
	private User admin;
	private User learner;

	@BeforeEach
	void setUp() {
		consents.deleteAll();
		documents.deleteAll();
		refreshTokens.deleteAll();
		authSessions.deleteAll();
		users.deleteAll();
		Instant now = Instant.now();
		documents.saveAndFlush(PolicyDocument.create(PolicyType.TERMS, "0.9", "약관",
			"약관 초안", null, now.minusSeconds(60), 0L, now.minusSeconds(60)));
		documents.saveAndFlush(PolicyDocument.create(PolicyType.PRIVACY, "0.9", "처리방침",
			"개인정보 초안", null, now.minusSeconds(60), 0L, now.minusSeconds(60)));
		admin = users.saveAndFlush(User.create("admin@example.com",
			passwordEncoder.encode("password123"), "관리자", UserRole.ADMIN));
		learner = users.saveAndFlush(User.create("learner@example.com",
			passwordEncoder.encode("password123"), "학습자", UserRole.LEARNER));
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
			.addFilters(traceIdFilter).build();
	}

	@Test
	void publicCurrentShowsEffectiveMetadataAndDetailRequiresExistingVersion() throws Exception {
		Instant now = Instant.now();
		documents.saveAndFlush(PolicyDocument.create(PolicyType.TERMS, "1.0", "새 약관",
			"시행 중 본문", "변경", now.minusSeconds(1), admin.getId(), now));
		documents.saveAndFlush(PolicyDocument.create(PolicyType.TERMS, "1.1", "예정 약관",
			"예정 본문", "예정", now.plusSeconds(3600), admin.getId(), now));
		mvc.perform(get("/api/policies/current"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2))
			.andExpect(jsonPath("$.data[0].version").value("1.0"))
			.andExpect(jsonPath("$.data[0].content").doesNotExist());
		mvc.perform(get("/api/policies/TERMS/0.9"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").value("약관 초안"));
		mvc.perform(get("/api/policies/TERMS/unknown"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("POLICY_NOT_FOUND"));
	}

	@Test
	void signupNeedsBothVersionsAndLoginHasNoPendingAfterSignup() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"new@example.com","password":"password123",
				 "name":"신규","role":"LEARNER"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("POLICY_CONSENT_REQUIRED"));
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"new@example.com","password":"password123",
				 "name":"신규","role":"LEARNER",
				 "consents":[{"type":"TERMS","version":"0.9"},
				             {"type":"PRIVACY","version":"0.9"}]}
				"""))
			.andExpect(status().isOk());
		User created = users.findByEmail("new@example.com").orElseThrow();
		assertThat(consents.findByUser_IdOrderByAgreedAtDescIdDesc(created.getId()))
			.hasSize(2);
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"new@example.com","password":"password123"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pendingConsents").isEmpty());
	}

	@Test
	void newCurrentVersionRequiresConsentAndRepeatDoesNotDuplicateHistory() throws Exception {
		Instant now = Instant.now();
		documents.saveAndFlush(PolicyDocument.create(PolicyType.TERMS, "1.0", "새 약관",
			"본문", "변경", now.minusSeconds(1), admin.getId(), now));
		String bearer = "Bearer " + jwtTokens.createAccessToken(learner);
		mvc.perform(get("/api/users/me/consents").header(HttpHeaders.AUTHORIZATION, bearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pending.length()").value(2));
		String body = """
			{"consents":[{"type":"TERMS","version":"1.0"},
			             {"type":"PRIVACY","version":"0.9"}]}
			""";
		for (int attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/users/me/consents")
				.header(HttpHeaders.AUTHORIZATION, bearer)
				.header("User-Agent", "policy-test")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.pending").isEmpty());
		}
		assertThat(consents.findByUser_IdOrderByAgreedAtDescIdDesc(learner.getId()))
			.hasSize(2);
		assertThat(consents.findByUser_IdOrderByAgreedAtDescIdDesc(learner.getId()))
			.allSatisfy(value -> {
				assertThat(value.getIp()).isNotBlank();
				assertThat(value.getUserAgent()).isEqualTo("policy-test");
			});
		mvc.perform(post("/api/users/me/consents")
			.header(HttpHeaders.AUTHORIZATION, bearer)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"consents":[{"type":"TERMS","version":"0.9"}]}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("POLICY_VERSION_MISMATCH"));
	}

	@Test
	void adminCanPublishFutureVersionAndNonAdminCannot() throws Exception {
		String body = """
			{"type":"TERMS","version":"1.0","title":"새 약관","content":"검토 완료 문구",
			 "summary":"변경","effectiveAt":"2099-01-01T00:00:00Z"}
			""";
		mvc.perform(post("/api/admin/policies")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokens.createAccessToken(learner))
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		String adminBearer = "Bearer " + jwtTokens.createAccessToken(admin);
		mvc.perform(post("/api/admin/policies")
			.header(HttpHeaders.AUTHORIZATION, adminBearer)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.version").value("1.0"));
		mvc.perform(post("/api/admin/policies")
			.header(HttpHeaders.AUTHORIZATION, adminBearer)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("POLICY_VERSION_EXISTS"));
		mvc.perform(get("/api/admin/policies/consent-stats")
			.header(HttpHeaders.AUTHORIZATION, adminBearer))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(2));
	}
}
