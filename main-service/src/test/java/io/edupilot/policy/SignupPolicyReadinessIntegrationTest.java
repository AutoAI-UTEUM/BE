package io.edupilot.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.auth.GoogleIdTokenVerifier;
import io.edupilot.auth.GoogleProfile;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.mail.EmailSender;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
	"spring.datasource.url=jdbc:h2:mem:signup-policy-readiness;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/signup-policy-readiness",
	"edupilot.admin.infra.enabled=false",
	"edupilot.mail.enabled=false",
	"edupilot.policy.signup-consent-required=true",
	"logging.level.root=INFO",
	"logging.level.org.hibernate.SQL=OFF"
})
@ActiveProfiles("jpa-context")
class SignupPolicyReadinessIntegrationTest {
	private static final String EMAIL = "synthetic-readiness@example.com";
	private static final String GOOGLE_SUB = "synthetic-readiness-subject";
	private static final String GOOGLE_TOKEN = "synthetic-readiness-id-token";
	private static final List<String> SIDE_EFFECT_TABLES = List.of(
		"policy_consents", "email_verification_tokens", "refresh_tokens", "auth_sessions",
		"email_outbox", "email_send_reservations", "email_deliveries", "users");

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PolicyDocumentRepository documents;
	@Autowired private PolicyConsentRepository consents;
	@Autowired private UserRepository users;
	@MockitoBean private GoogleIdTokenVerifier googleVerifier;
	@MockitoBean private EmailSender emailSender;
	private final ObjectMapper mapper = new ObjectMapper();
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		for (String table : SIDE_EFFECT_TABLES) {
			jdbc.update("delete from " + table);
		}
		documents.deleteAll();
		when(googleVerifier.verify(GOOGLE_TOKEN))
			.thenReturn(new GoogleProfile(GOOGLE_SUB, EMAIL, "Synthetic readiness user"));
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
			.addFilters(traceIdFilter).build();
	}

	@ParameterizedTest(name = "{0}: {1} {2} leaves no required effective document")
	@MethodSource("documentGaps")
	void mandatorySignupRejectsAnEmptyRequiredSet(String path, PolicyType type, Gap gap)
		throws Exception {
		installGap(type, gap);
		// A supplied choice cannot turn a missing, notice-only or future document into a requirement.
		assertRejectedWithoutSideEffects(path, List.of(choice(type, "1.0")),
			503, "SIGNUP_POLICY_NOT_READY");
	}

	@ParameterizedTest(name = "{0}: no required documents, input {1}")
	@MethodSource("unreadyInputs")
	void setupFailureTakesPrecedenceOverMissingOrSuppliedChoices(String path, String input)
		throws Exception {
		if (input.equals("notice-only")) {
			for (PolicyType type : PolicyType.values()) {
				publishFixture(type, "0.9", false, Instant.EPOCH);
			}
		}
		List<Map<String, String>> choices = switch (input) {
			case "omitted" -> null;
			case "empty" -> List.of();
			default -> List.of(choice(PolicyType.TERMS, "0.9"), choice(PolicyType.PRIVACY, "0.9"));
		};
		assertRejectedWithoutSideEffects(path, choices, 503, "SIGNUP_POLICY_NOT_READY");
	}

	@ParameterizedTest(name = "{0}: only {1} is required; the other type is {2}")
	@MethodSource("documentGaps")
	void aNonemptyRequiredSetKeepsTheExistingMetadataContract(String path, PolicyType otherType, Gap gap)
		throws Exception {
		installGap(otherType, gap);
		PolicyType requiredType = otherType == PolicyType.TERMS ? PolicyType.PRIVACY : PolicyType.TERMS;
		publishFixture(requiredType, "1.0", true, Instant.EPOCH);
		MvcResult result = submit(path, List.of(choice(requiredType, "1.0")));
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(consents.findAll()).singleElement().satisfies(consent -> {
			assertThat(consent.getPolicyType()).isEqualTo(requiredType);
			assertThat(consent.getPolicyVersion()).isEqualTo("1.0");
		});
		assertThat(users.count()).isEqualTo(1);
		verifyNoInteractions(emailSender);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/auth/signup", "/api/auth/google"})
	void twoEffectiveRequiredDocumentsRecordBothVersionsAndIgnoreScheduledVersions(String path)
		throws Exception {
		for (PolicyType type : PolicyType.values()) {
			publishFixture(type, "1.0", true, Instant.EPOCH);
			publishFixture(type, "2.0", true, Instant.now().plusSeconds(86400));
		}
		MvcResult result = submit(path,
			List.of(choice(PolicyType.TERMS, "1.0"), choice(PolicyType.PRIVACY, "1.0")));
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(consents.findAll()).extracting(PolicyConsent::getPolicyType)
			.containsExactlyInAnyOrder(PolicyType.TERMS, PolicyType.PRIVACY);
		assertThat(consents.findAll()).allSatisfy(consent ->
			assertThat(consent.getPolicyVersion()).isEqualTo("1.0"));
		assertThat(users.count()).isEqualTo(1);
		assertThat(rowCount("email_verification_tokens")).isEqualTo(1);
		assertThat(rowCount("auth_sessions")).isEqualTo(path.endsWith("google") ? 1 : 0);
		assertThat(rowCount("refresh_tokens")).isEqualTo(path.endsWith("google") ? 1 : 0);
		verifyNoInteractions(emailSender);
	}

	@ParameterizedTest(name = "{0}: ready documents but user input {1}")
	@MethodSource("invalidChoices")
	void readySetupDistinguishesUserConsentErrors(String path, String input) throws Exception {
		for (PolicyType type : PolicyType.values()) {
			publishFixture(type, "1.0", true, Instant.EPOCH);
		}
		List<Map<String, String>> choices = switch (input) {
			case "omitted" -> null;
			case "empty" -> List.of();
			case "missing-TERMS" -> List.of(choice(PolicyType.PRIVACY, "1.0"));
			case "missing-PRIVACY" -> List.of(choice(PolicyType.TERMS, "1.0"));
			case "duplicate" -> List.of(choice(PolicyType.TERMS, "1.0"),
				choice(PolicyType.TERMS, "1.0"), choice(PolicyType.PRIVACY, "1.0"));
			default -> List.of(choice(PolicyType.TERMS, "2.0"), choice(PolicyType.PRIVACY, "1.0"));
		};
		assertRejectedWithoutSideEffects(path, choices, 400, "POLICY_CONSENT_REQUIRED");
	}

	@Test
	void openApiDistinguishesSignupSuccessConsentErrorAndPolicySetupFailure() throws Exception {
		MvcResult result = mvc.perform(get("/v3/api-docs")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode specification = mapper.readTree(result.getResponse().getContentAsString());
		for (String path : List.of("/api/auth/signup", "/api/auth/google")) {
			JsonNode responses = specification.path("paths").path(path).path("post").path("responses");
			assertThat(responses.has("200")).as(path + " success").isTrue();
			assertThat(responses.path("400").path("description").asText())
				.contains("POLICY_CONSENT_REQUIRED");
			assertThat(responses.path("503").path("description").asText())
				.contains("SIGNUP_POLICY_NOT_READY");
		}
	}

	@Test
	void existingGoogleLoginDoesNotDependOnNewSignupPolicyReadiness() throws Exception {
		users.saveAndFlush(User.createGoogle(EMAIL, "!oauth:google", "Existing synthetic user",
			UserRole.LEARNER, null, false, null, null, null, GOOGLE_SUB));
		MvcResult result = mvc.perform(post("/api/auth/google").contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("idToken", GOOGLE_TOKEN)))).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(users.count()).isEqualTo(1);
		assertThat(consents.count()).isZero();
		assertThat(rowCount("email_verification_tokens")).isZero();
		assertThat(rowCount("auth_sessions")).isEqualTo(1);
		assertThat(rowCount("refresh_tokens")).isEqualTo(1);
		verifyNoInteractions(emailSender);
	}

	private void installGap(PolicyType type, Gap gap) {
		switch (gap) {
			case MISSING -> { }
			case NON_REQUIRED -> publishFixture(type, "1.0", false, Instant.EPOCH);
			case FUTURE_ONLY -> publishFixture(type, "1.0", true, Instant.now().plusSeconds(86400));
			case SUPERSEDED -> {
				publishFixture(type, "0.9", true, Instant.EPOCH);
				publishFixture(type, "1.0", false, Instant.now().minusSeconds(3600));
			}
		}
	}

	private void publishFixture(PolicyType type, String version, boolean required, Instant effectiveAt) {
		documents.saveAndFlush(PolicyDocument.create(type, version, "Synthetic " + type,
			"Synthetic test fixture; not a published operating policy.", null, required,
			effectiveAt, 0L, Instant.EPOCH));
	}

	private void assertRejectedWithoutSideEffects(String path, List<Map<String, String>> choices,
		int status, String code) throws Exception {
		MvcResult result = submit(path, choices);
		assertThat(result.getResponse().getStatus()).isEqualTo(status);
		JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("error").path("code").asText()).isEqualTo(code);
		assertThat(body.has("data")).isFalse();
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
		for (String table : SIDE_EFFECT_TABLES) {
			assertThat(rowCount(table)).as(table).isZero();
		}
		verifyNoInteractions(emailSender);
	}

	private int rowCount(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private MvcResult submit(String path, List<Map<String, String>> choices) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		if (path.endsWith("google")) {
			body.put("idToken", GOOGLE_TOKEN);
		} else {
			body.put("email", EMAIL);
			body.put("password", "synthetic-readiness-password-540");
			body.put("name", "Synthetic readiness user");
		}
		body.put("role", "LEARNER");
		body.put("dateOfBirth", "1990-01-01");
		body.put("learningEmailOptIn", false);
		if (choices != null) {
			body.put("consents", choices);
		}
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(body))).andReturn();
	}

	private static Map<String, String> choice(PolicyType type, String version) {
		return Map.of("type", type.name(), "version", version);
	}

	private static Stream<Arguments> documentGaps() {
		List<Arguments> cases = new ArrayList<>();
		for (String path : List.of("/api/auth/signup", "/api/auth/google")) {
			for (PolicyType type : PolicyType.values()) {
				for (Gap gap : Gap.values()) {
					cases.add(Arguments.of(path, type, gap));
				}
			}
		}
		return cases.stream();
	}

	private static Stream<Arguments> unreadyInputs() {
		return inputs("omitted", "empty", "supplied", "notice-only");
	}

	private static Stream<Arguments> invalidChoices() {
		return inputs("omitted", "empty", "missing-TERMS", "missing-PRIVACY", "duplicate", "future-version");
	}

	private static Stream<Arguments> inputs(String... inputs) {
		return Stream.of("/api/auth/signup", "/api/auth/google")
			.flatMap(path -> Stream.of(inputs).map(input -> Arguments.of(path, input)));
	}

	// PolicyDocument has no independent active flag. SUPERSEDED models a version that is no longer current.
	private enum Gap { MISSING, NON_REQUIRED, FUTURE_ONLY, SUPERSEDED }
}
