package io.edupilot.composer;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.MainServiceApplication;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.material.*;
import io.edupilot.session.*;
import io.edupilot.user.*;

@SpringBootTest(classes = MainServiceApplication.class, properties = {
	"spring.datasource.url=jdbc:h2:mem:composer-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/composer-security",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging",
	"edupilot.composer.enabled=true", "edupilot.composer.policy-confirmed=false"
})
@ActiveProfiles("jpa-context")
class ComposerSecurityJpaTest {
	@Autowired WebApplicationContext context;
	@Autowired UserRepository users;
	@Autowired LearningMaterialRepository materials;
	@Autowired LearningSessionRepository sessions;
	@Autowired ChatMessageRepository messages;
	@Autowired JwtTokenProvider tokens;
	@MockitoBean ComposerAiClient ai;
	MockMvc mvc;
	User learner;
	LearningMaterial material;
	LearningSession session;
	String endpoint;

	@BeforeEach void setup() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		learner = adult();
		material = LearningMaterial.create(learner, "Synthetic material", "synthetic/" + UUID.randomUUID());
		material.markReady(1); material = materials.saveAndFlush(material);
		session = sessions.saveAndFlush(LearningSession.create(learner, material));
		endpoint = "/api/sessions/" + session.getId() + "/composer-prediction";
	}
	User adult() {
		var user = User.create("synthetic-" + UUID.randomUUID() + "@example.invalid", "synthetic-hash", "Synthetic adult");
		user.verifyEmail(Instant.parse("2020-01-01T00:00:00Z"));
		ReflectionTestUtils.setField(user, "dateOfBirth", LocalDate.of(2000, 1, 1));
		return users.saveAndFlush(user);
	}
	String bearer() { return "Bearer " + tokens.createAccessToken(learner); }

	@Test void anonymousAndInvalidTokenAreRejectedByRealSecurityChain() throws Exception {
		mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{\"sourceMessageId\":50}"))
			.andExpect(status().isUnauthorized());
		mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-synthetic")
			.contentType(MediaType.APPLICATION_JSON).content("{\"sourceMessageId\":50}"))
			.andExpect(status().isUnauthorized()); verifyNoInteractions(ai);
	}
	@Test void ownerCannotPredictAnotherUsersSession() throws Exception {
		var foreign = sessions.saveAndFlush(LearningSession.create(adult(), material));
		mvc.perform(post("/api/sessions/" + foreign.getId() + "/composer-prediction")
			.header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"sourceMessageId\":50}"))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
		verifyNoInteractions(ai);
	}
	@Test void unverifiedEmailAndAgeAreNotBypassedByOptionalFeature() throws Exception {
		ReflectionTestUtils.setField(learner, "emailVerificationState", EmailVerificationState.UNKNOWN);
		ReflectionTestUtils.setField(learner, "emailVerifiedAt", null); users.saveAndFlush(learner);
		mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sourceMessageId\":50}")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		learner.verifyEmail(Instant.now()); ReflectionTestUtils.setField(learner, "dateOfBirth", LocalDate.now().minusYears(10));
		users.saveAndFlush(learner);
		mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sourceMessageId\":50}")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("AGE_VERIFICATION_REQUIRED")); verifyNoInteractions(ai);
	}
	@Test void deletedMaterialIsNotAnAccessiblePredictionSource() throws Exception {
		material.delete(); materials.saveAndFlush(material);
		mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sourceMessageId\":50}")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("MATERIAL_NOT_FOUND")); verifyNoInteractions(ai);
	}
	@Test void changedDatabaseRoleInvalidatesExistingToken() throws Exception {
		String old = bearer(); ReflectionTestUtils.setField(learner, "role", UserRole.ADMIN); users.saveAndFlush(learner);
		mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, old).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sourceMessageId\":50}")).andExpect(status().isUnauthorized()); verifyNoInteractions(ai);
	}
	@Test void validAccountStillMakesZeroAiCallsWhenPolicyIsUnconfirmed() throws Exception {
		session.moveTo(1, PageStatus.EXPLAINED, java.util.List.of(UiAction.completeSession()));
		session = sessions.saveAndFlush(session);
		var source = messages.saveAndFlush(ChatMessage.ai(session, MessageType.EXPLANATION, "Synthetic public explanation"));
		var result = mvc.perform(post(endpoint).header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sourceMessageId\":" + source.getId() + "}")).andExpect(status().isOk())
			.andExpect(request().asyncStarted()).andReturn();
		mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("\"schemaVersion\":\"1.0\"")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("\"sourceTurnId\":\"message-" + source.getId() + "\"")))
			.andExpect(content().string(org.hamcrest.Matchers.containsString("\"warnings\":[]")))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"reason\":"))))
			.andExpect(content().string(org.hamcrest.Matchers.matchesPattern("(?s).*\\\"contextVersion\\\":\\\"[a-f0-9]{64}\\\".*")));
		verifyNoInteractions(ai);
	}
}
