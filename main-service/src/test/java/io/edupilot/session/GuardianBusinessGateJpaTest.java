package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.aiusage.AiQuotaProperties;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.auth.EmailVerificationGate;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialExtractionPersistenceService;
import io.edupilot.material.MaterialExtractionService;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.AccountAccessCohort;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

/** Real account/permission state and JWTs; synthetic data and mocked external IO only. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-business;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-business",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging", "edupilot.guardian.web.enabled=false"
})
@ActiveProfiles("jpa-context")
class GuardianBusinessGateJpaTest {
	@Autowired private WebApplicationContext context;
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private EmailVerificationGate eligibility;
	@Autowired private SessionStreamAccessGuard streamAccess;
	@Autowired private MaterialAccessService materialAccess;
	@Autowired private MaterialExtractionService extraction;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private Clock clock;
	@MockitoBean private MaterialExtractionPersistenceService extractionState;
	@MockitoBean private AiClient ai;
	@MockitoBean private FileStorage files;
	private MockMvc mvc;

	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry properties) {
		String url = System.getenv("GUARDIAN_BUSINESS_MYSQL_URL");
		if (url == null || url.isBlank()) return;
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/guardian_business_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Guardian business tests require the owned disposable loopback schema");
		}
		properties.add("spring.datasource.url", () -> url);
		properties.add("spring.datasource.username", () -> "root");
		properties.add("spring.datasource.password", () -> "");
		properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}

	@BeforeEach
	void setup() {
		if (System.getenv("GUARDIAN_BUSINESS_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("guardian_business_synthetic");
		}
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		when(files.load(anyString())).thenReturn(new ByteArrayResource("%PDF-synthetic-only".getBytes()));
	}

	static Stream<Arguments> statesAndRoles() {
		return Stream.of(UserRole.values()).flatMap(role -> Stream.of(AgeVerificationState.values())
			.map(state -> Arguments.of(role, state)));
	}
	static Stream<AgeVerificationState> states() { return Stream.of(AgeVerificationState.values()); }

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void verifiedNewAccountCannotReadProtectedApiFileOrOpenSse(UserRole role, AgeVerificationState state) throws Exception {
		Fixture fixture = fixture(role, state, false);
		for (String endpoint : new String[]{"/api/materials", "/api/materials/" + fixture.material.getId() + "/file",
			"/api/sessions/" + fixture.session.getId() + "/stream", "/api/notes"}) {
			mvc.perform(get(endpoint).header("Authorization", bearer(fixture.user)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value(error(state).name()))
				.andExpect(header().string("Cache-Control", "no-store"));
		}
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void newAccountCanStillReadSelfAndVerificationStatus(UserRole role, AgeVerificationState state) throws Exception {
		Fixture fixture = fixture(role, state, false);
		mvc.perform(get("/api/users/me").header("Authorization", bearer(fixture.user))).andExpect(status().isOk());
		mvc.perform(get("/api/auth/email-verification/status").header("Authorization", bearer(fixture.user)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.emailVerification").value("VERIFIED"));
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void workerDoesNotLoadPdfOrTransmitToAiForUnconfirmedNewOwner(UserRole role, AgeVerificationState state) {
		Fixture fixture = fixture(role, state, false);
		when(extractionState.snapshot(fixture.material.getId())).thenReturn(Optional.of(
			new MaterialExtractionPersistenceService.ExtractionSnapshot(fixture.material.getId(), fixture.user.getId(), fixture.material.getStorageKey())));
		extraction.extract(fixture.material.getId(), "synthetic-guardian-worker");
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void quotaDisabledAndAdminRoleDoNotGrantAgeOrGuardianEligibility(UserRole role, AgeVerificationState state) {
		Fixture fixture = fixture(role, state, false);
		AiUsageLogRepository usage = mock(AiUsageLogRepository.class);
		var quota = new AiQuotaService(usage, new AiQuotaProperties(false, 200, 500), Clock.systemUTC(), eligibility);
		assertError(() -> quota.checkQuota(fixture.user.getId(), role), error(state));
		verifyNoInteractions(usage, files, ai);
	}

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void directStreamAndCompletionAccessRejectUnconfirmedNewAccount(UserRole role, AgeVerificationState state) {
		Fixture fixture = fixture(role, state, false);
		assertError(() -> streamAccess.captureRole(fixture.user.getId()), error(state));
		assertError(() -> streamAccess.assertAccessible(fixture.user.getId(), fixture.session.getId(), role), error(state));
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void missingEmailEvidenceIsRejectedBeforeAgeAndGuardianState(UserRole role, AgeVerificationState state) throws Exception {
		Fixture fixture = fixture(role, state, false);
		jdbc.update("update users set email_verification_state='PENDING', email_verified_at=null where id=?", fixture.user.getId());
		assertError(() -> eligibility.requireVerified(fixture.user.getId()), ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		assertError(() -> streamAccess.assertAccessible(fixture.user.getId(), fixture.session.getId(), role), ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		mvc.perform(get("/api/materials").header("Authorization", bearer(fixture.user)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("statesAndRoles")
	void legacyExceptionPreservesAccessWithoutApprovingAgeOrGuardian(UserRole role, AgeVerificationState state) throws Exception {
		Fixture fixture = fixture(role, state, true);
		eligibility.requireVerified(fixture.user.getId());
		streamAccess.assertAccessible(fixture.user.getId(), fixture.session.getId(), role);
		mvc.perform(get("/api/materials").header("Authorization", bearer(fixture.user))).andExpect(status().isOk());
		User stored = users.findById(fixture.user.getId()).orElseThrow();
		assertThat(stored.getAccessCohort()).isEqualTo(AccountAccessCohort.LEGACY_EXEMPT);
		assertThat(stored.getAgeVerificationState()).isEqualTo(state);
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("states")
	void materialAndSessionOwnerChecksApplyTheSamePolicy(AgeVerificationState state) {
		Fixture fixture = fixture(UserRole.LEARNER, state, false);
		assertError(() -> eligibility.requireMaterialOwnerVerified(fixture.material.getId()), error(state));
		assertError(() -> eligibility.requireSessionOwnerVerified(fixture.session.getId()), error(state));
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("states")
	void connectedStreamStopsBeforeAnotherPayloadWhenCurrentEligibilityIsAbsent(AgeVerificationState state) throws Exception {
		Fixture fixture = fixture(UserRole.LEARNER, state, true);
		RecordingEmitter emitter = new RecordingEmitter();
		SessionStreamService streams = new SessionStreamService(sessions, materialAccess, streamAccess, () -> emitter);
		try {
			streams.connect(fixture.user.getId(), fixture.session.getId());
			AiStreamCancellation upstream = new AiStreamCancellation();
			SessionStreamConnection connection = streams.beginTurn(fixture.user.getId(), fixture.session.getId(), "synthetic-guardian-turn", upstream).orElseThrow();
			// Test-only committed change. No production cohort mutation or approval endpoint is added.
			jdbc.update("update users set access_cohort='NEW_SIGNUP' where id=?", fixture.user.getId());
			try { connection.send(TurnStreamEvent.contentDelta("Synthetic protected content")); }
			catch (AiClientException expectedInterruption) { assertThat(expectedInterruption.retryable()).isFalse(); }
			assertThat(emitter.deliveries.get()).isEqualTo(1); // only the previously authorized ready event
			assertThat(connection.isClosed()).isTrue();
			assertThat(connection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.ACCESS_REVOKED);
			assertThat(upstream.isCancelled()).isTrue();
			assertThat(upstream.isUserCancelled()).isFalse();
		} finally { streams.shutdown(); }
		verifyNoInteractions(files, ai);
	}

	@ParameterizedTest
	@MethodSource("states")
	void callerManagedLegacySnapshotCannotBypassCommittedNewState(AgeVerificationState state) {
		Fixture fixture = fixture(UserRole.LEARNER, state, true);
		new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
			User cached = users.findById(fixture.user.getId()).orElseThrow();
			assertThat(cached.isLegacyAccessExempt()).isTrue();
			TransactionTemplate independent = new TransactionTemplate(transactions);
			independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			independent.executeWithoutResult(inner -> jdbc.update("update users set access_cohort='NEW_SIGNUP' where id=?", fixture.user.getId()));
			assertThat(cached.isLegacyAccessExempt()).isTrue();
			assertError(() -> eligibility.requireVerified(fixture.user.getId()), error(state));
			assertError(() -> streamAccess.assertAccessible(fixture.user.getId(), fixture.session.getId(), UserRole.LEARNER), error(state));
		});
		verifyNoInteractions(files, ai);
	}

	private Fixture fixture(UserRole role, AgeVerificationState state, boolean legacy) {
		return fixture(role, state, legacy, LocalDate.of(io.edupilot.guardian.BirthdatePolicy.today(clock).getYear() - 14, 12, 31));
	}
	private Fixture fixture(UserRole role, AgeVerificationState state, boolean legacy, LocalDate date) {
		User user = User.create("synthetic-guardian-" + UUID.randomUUID() + "@example.com", "synthetic-hash", "Synthetic guardian actor", role);
		user.recordSignupDateOfBirth(date);
		user.verifyEmail(Instant.parse("2020-01-01T00:00:00Z"));
		if (state == AgeVerificationState.MANUAL_PENDING) user.beginGuardianVerification();
		if (state == AgeVerificationState.TEAM_APPROVED) {
			// 상태 이름만 승인인 불완전한 합성 기록도 OFF·증거 미확인 경계를 우회할 수 없어야 한다.
			// 실제 승인 방법, 동의 증거, 유효 기간이나 AI 범위를 부여하지 않는다.
			org.springframework.test.util.ReflectionTestUtils.setField(user, "ageVerificationState", state);
		}
		user = users.saveAndFlush(user);
		if (legacy) {
			// Only migration/grandfathered synthetic actors use this exception, never new-account success.
			jdbc.update("update users set access_cohort='LEGACY_EXEMPT' where id=?", user.getId());
			user = users.findById(user.getId()).orElseThrow();
		}
		LearningMaterial material = LearningMaterial.create(user, "Synthetic PDF", "materials/synthetic-" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		material = materials.saveAndFlush(material);
		LearningSession session = sessions.saveAndFlush(LearningSession.create(user, material));
		return new Fixture(user, material, session);
	}
	@ParameterizedTest @MethodSource("statesAndRoles")
	void newAccountsOutsideTheGuardianYearCohortCanUseProtectedFilesAndStreamGuardsWithoutApprovalState(UserRole role, AgeVerificationState state) throws Exception {
		Fixture fixture=fixture(role,state,false,LocalDate.of(io.edupilot.guardian.BirthdatePolicy.today(clock).getYear()-15,12,31));
		mvc.perform(get("/api/materials/"+fixture.material.getId()+"/file").header("Authorization",bearer(fixture.user))).andExpect(status().isOk());
		eligibility.requireVerified(fixture.user.getId());eligibility.requireMaterialOwnerVerified(fixture.material.getId());eligibility.requireSessionOwnerVerified(fixture.session.getId());
		assertThat(streamAccess.captureRole(fixture.user.getId())).isEqualTo(role);
		streamAccess.assertAccessible(fixture.user.getId(),fixture.session.getId(),role);
		assertThat(users.findById(fixture.user.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(state);
		verifyNoInteractions(ai);
	}
	private String bearer(User user) { return "Bearer " + tokens.createAccessToken(user); }
	private static ErrorCode error(AgeVerificationState state) {
		return state == AgeVerificationState.MANUAL_PENDING ? ErrorCode.GUARDIAN_VERIFICATION_PENDING : ErrorCode.AGE_VERIFICATION_REQUIRED;
	}
	private static void assertError(Runnable action, ErrorCode expected) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			failure -> assertThat(failure.errorCode()).isEqualTo(expected));
	}
	private record Fixture(User user, LearningMaterial material, LearningSession session) { }
	private static final class RecordingEmitter extends SseEmitter {
		private final AtomicInteger deliveries = new AtomicInteger();
		@Override public void send(SseEventBuilder event) throws IOException { deliveries.incrementAndGet(); }
	}
}
