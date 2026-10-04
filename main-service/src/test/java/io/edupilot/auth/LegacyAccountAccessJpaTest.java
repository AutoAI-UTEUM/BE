package io.edupilot.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.global.error.*;
import io.edupilot.guardian.*;
import io.edupilot.material.*;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.session.*;
import io.edupilot.user.*;
import io.edupilot.user.dto.UserResponse;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:legacy-access;MODE=MySQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/legacy-access"
})
@ActiveProfiles("jpa-context")
class LegacyAccountAccessJpaTest {
	@DynamicPropertySource
	static void isolatedMysql(DynamicPropertyRegistry registry) {
		if (!"true".equals(System.getenv("LEGACY_ACCESS_MYSQL"))) { return; }
		registry.add("spring.datasource.url", () -> "jdbc:mysql://127.0.0.1:33316/legacy_access_synthetic");
		registry.add("spring.datasource.username", () -> "root");
		registry.add("spring.datasource.password", () -> "");
		registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private EmailVerificationGate email;
	@Autowired private AgeEligibilityGate age;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private WebApplicationContext context;
	@MockitoBean private AiClient ai;
	@MockitoBean private FileStorage files;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		if ("true".equals(System.getenv("LEGACY_ACCESS_MYSQL"))) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("legacy_access_synthetic");
		}
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void migratedUnknownAccountKeepsAccessWithoutDobEmailOrGuardianEvidence() throws Exception {
		User legacy = legacy(UserRole.LEARNER);
		assertNoVerificationEvidence(legacy);
		email.requireVerified(legacy.getId());
		age.requireEligible(legacy.getId());
		var material = material(legacy);
		var session = sessions.saveAndFlush(LearningSession.create(legacy, material));
		when(files.load(material.getStorageKey())).thenReturn(new ByteArrayResource("%PDF-synthetic".getBytes()));
		assertStatus(legacy, "/api/materials/" + material.getId() + "/file", 200);
		assertStatus(legacy, "/api/sessions/" + session.getId() + "/stream", 200);
		assertStatus(legacy, "/api/users/me", 200);
		assertThat(UserResponse.from(legacy).emailVerificationRequired()).isFalse();
		assertThat(io.edupilot.auth.dto.EmailVerificationResponse.from(legacy).emailVerificationRequired()).isFalse();
		assertNoVerificationEvidence(users.findById(legacy.getId()).orElseThrow());
		verify(files).load(material.getStorageKey());
		verifyNoInteractions(ai);
	}

	@ParameterizedTest
	@EnumSource(UserRole.class)
	void newAccountsNeverInheritExemptionFromMissingDobUnknownEmailOrOldTimestamp(UserRole role) throws Exception {
		User account = user(role);
		jdbc.update("update users set created_at=? where id=?", java.sql.Timestamp.from(Instant.EPOCH), account.getId());
		assertThat(account.getAccessCohort()).isEqualTo(AccountAccessCohort.NEW_SIGNUP);
		assertError(() -> age.requireEligible(account.getId()), ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertError(() -> email.requireVerified(account.getId()), ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		assertStatus(account, "/api/materials/1/file", 403);
		account.beginEmailVerification();
		users.saveAndFlush(account);
		assertError(() -> email.requireVerified(account.getId()), ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		assertThat(UserResponse.from(account).emailVerificationRequired()).isTrue();
		verifyNoInteractions(ai, files);
	}

	@Test
	void legacyPendingIntakeIsAnAccessExceptionAndRemainsUnapproved() {
		User legacy = legacy(UserRole.LEARNER);
		legacy.beginEmailVerification();
		legacy.beginGuardianVerification();
		users.saveAndFlush(legacy);
		email.requireVerified(legacy.getId());
		age.requireEligible(legacy.getId());
		var stored = users.findById(legacy.getId()).orElseThrow();
		assertThat(stored.getEmailVerificationState()).isEqualTo(EmailVerificationState.PENDING);
		assertThat(stored.getAgeVerificationState()).isEqualTo(AgeVerificationState.MANUAL_PENDING);
		assertThat(stored.getEmailVerifiedAt()).isNull();
		verifyNoInteractions(ai, files);
	}

	@Test
	void legacyExemptionDoesNotAllowAnotherUsersFileOrSession() throws Exception {
		User owner = legacy(UserRole.LEARNER);
		User other = legacy(UserRole.LEARNER);
		var material = material(owner);
		var session = sessions.saveAndFlush(LearningSession.create(owner, material));
		assertStatus(other, "/api/materials/" + material.getId() + "/file", 404);
		assertStatus(other, "/api/sessions/" + session.getId() + "/stream", 404);
		verifyNoInteractions(ai, files);
	}

	@Test
	void legacyExemptionNeverOverridesSuspensionOrWithdrawal() throws Exception {
		User legacy = legacy(UserRole.LEARNER);
		legacy.suspend("Synthetic suspension", 1L, Instant.now());
		users.saveAndFlush(legacy);
		assertError(() -> age.requireEligible(legacy.getId()), ErrorCode.ACCOUNT_SUSPENDED);
		assertError(() -> email.requireVerified(legacy.getId()), ErrorCode.ACCOUNT_SUSPENDED);
		assertStatus(legacy, "/api/materials/1/file", 401);
		legacy.withdraw();
		users.saveAndFlush(legacy);
		assertError(() -> age.requireEligible(legacy.getId()), ErrorCode.USER_INACTIVE);
		assertError(() -> email.requireVerified(legacy.getId()), ErrorCode.USER_INACTIVE);
		assertStatus(legacy, "/api/materials/1/file", 401);
		verifyNoInteractions(ai, files);
	}

	private User legacy(UserRole role) {
		User user = user(role);
		// Only the migration has this authority; no public DTO or domain mutator can grant it.
		jdbc.update("update users set access_cohort='LEGACY_EXEMPT' where id=?", user.getId());
		return users.findById(user.getId()).orElseThrow();
	}
	private User user(UserRole role) {
		return users.saveAndFlush(User.create("synthetic-" + UUID.randomUUID() + "@example.com", "hash", "Synthetic", role));
	}
	private LearningMaterial material(User owner) {
		var material = LearningMaterial.create(owner, "Synthetic PDF", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		return materials.saveAndFlush(material);
	}
	private void assertNoVerificationEvidence(User user) {
		assertThat(user.getDateOfBirth()).isNull();
		assertThat(user.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
		assertThat(user.getEmailVerificationState()).isEqualTo(EmailVerificationState.UNKNOWN);
		assertThat(user.getEmailVerifiedAt()).isNull();
		assertThat(user.isEmailVerified()).isFalse();
	}
	private void assertStatus(User user, String path, int expected) throws Exception {
		var result = mvc.perform(get(path).header("Authorization", "Bearer " + tokens.createAccessToken(user))).andReturn();
		if (result.getRequest().isAsyncStarted()) { result.getRequest().getAsyncContext().complete(); }
		assertThat(result.getResponse().getStatus()).as(path).isEqualTo(expected);
	}
	private void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			exception -> assertThat(exception.errorCode()).isEqualTo(expected));
	}
}
