package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.assessment.LearningSupportPipeline;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.TurnClaimService;
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import tools.jackson.databind.node.StringNode;

/** Committed synthetic H2 fixtures; no AI, mail or operational data leaves the test. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:quiz-post-consent;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/quiz-post-consent", "edupilot.mail.enabled=false", "edupilot.mail.provider=logging",
	"edupilot.ai.quota.enabled=false",
	// Test notices and durations are synthetic; they do not decide operating policies.
	"edupilot.guardian.team.enabled=true", "edupilot.guardian.team.policy-confirmed=true",
	"edupilot.guardian.team.portal-base-url=https://synthetic.example.test",
	"edupilot.guardian.team.notice-url=https://synthetic.example.test/notice",
	"edupilot.guardian.team.notice-version=synthetic-1",
	"edupilot.guardian.team.notice-digest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
	"edupilot.guardian.team.collection-items-text=Synthetic test information",
	"edupilot.guardian.team.purposes-text=Synthetic test verification",
	"edupilot.guardian.team.retention-text=Synthetic test retention notice",
	"edupilot.guardian.team.refusal-text=Synthetic test refusal notice",
	"edupilot.guardian.team.reply-contact=reviewer@synthetic.example.test",
	"edupilot.guardian.team.reply-channel=EMAIL_REPLY", "edupilot.guardian.team.reviewer-ids=1",
	"edupilot.guardian.team.required-scopes=SERVICE", "edupilot.guardian.team.optional-ai-scope=EXTERNAL_AI",
	"edupilot.guardian.team.optional-consent-text=Synthetic test external AI consent",
	"edupilot.guardian.team.link-ttl=PT1H", "edupilot.guardian.team.request-ttl=PT96H",
	"edupilot.guardian.team.approved-evidence-retention=P30D", "edupilot.guardian.team.approval-validity=P30D"
})
@ActiveProfiles("jpa-context")
class QuizPostGradingConsentJpaTest {
	private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
	private static final String DIAGNOSTIC_PROMPT = "Synthetic diagnosis from the original consent generation";
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private QuizRepository quizzes;
	@Autowired private QuizSubmissionPreparationService preparation;
	@Autowired private QuizGradingService grading;
	@Autowired private QuizSubmissionPersistenceService persistence;
	@Autowired private QuizProperties properties;
	@Autowired private LearningSupportPipeline pipeline;
	@Autowired private TurnClaimService claims;
	@Autowired private MaterialAccessService access;
	@Autowired private GuardianConsentFence fence;
	@Autowired private GuardianTeamProperties policy;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoBean private AiUsageService usage;
	@MockitoBean private Clock clock;

	@BeforeEach
	void configureSyntheticAi() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		assertThat(policy.ready()).isTrue();
		when(ai.quizAssessment(any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new QuizAssessmentResponse("1.0", "Synthetic understanding", List.of(), List.of(),
				List.of(), "Synthetic next direction", List.of(), List.of(), null);
		});
		when(ai.diagnosis(any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new DiagnosisResponse("1.0", List.of(), List.of(), DIAGNOSTIC_PROMPT, List.of(), "Synthetic repair", null);
		});
	}

	@ParameterizedTest
	@EnumSource(value = QuizType.class, names = {"MCQ", "OX"})
	void reapprovalAfterCommittedDiagnosisBeforeHookReturnSuppressesOriginalGenerationJson(QuizType type) throws Exception {
		Fixture fixture = fixture(type);
		CountDownLatch diagnosisSaved = new CountDownLatch(1);
		CountDownLatch returnHook = new CountDownLatch(1);
		QuizPostGradingHook barrier = context -> {
			assertThat(context.guardianConsent()).isNull();
			var result = pipeline.onGraded(context);
			assertThat(result.guardianConsent()).isEqualTo(new GuardianConsentFence.Snapshot(fixture.child(), fixture.epoch()));
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertSavedHistory(fixture);
			diagnosisSaved.countDown();
			await(returnHook);
			return result;
		};
		QuizSubmissionService service = new QuizSubmissionService(preparation, grading, persistence, properties,
			barrier, claims, access, fence);
		AtomicReference<QuizSubmitResponse> returned = new AtomicReference<>();
		AtomicReference<BusinessException> rejected = new AtomicReference<>();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var workflow = executor.submit(() -> {
				try {
					returned.set(service.submit(fixture.child(), fixture.quiz(), request(type)));
				} catch (BusinessException failure) {
					rejected.set(failure);
				}
			});
			try {
				await(diagnosisSaved);
				transaction().executeWithoutResult(status -> {
					User child = users.findByIdForUpdate(fixture.child()).orElseThrow();
					child.clearGuardianTeamApproval(false);
					approve(child, true);
					users.flush();
				});
				User current = users.findById(fixture.child()).orElseThrow();
				assertThat(current.getGuardianConsentEpoch()).isGreaterThan(fixture.epoch());
				assertThat(UserBusinessAccessState.from(current).aiEligibilityFailure(
					Clock.fixed(NOW, ZoneOffset.UTC), policy.ready(), policy.configurationDigest())).isNull();
			} finally {
				returnHook.countDown();
			}
			workflow.get(10, TimeUnit.SECONDS);
		}
		String returnedJson = returned.get() == null ? null : new ObjectMapper().writeValueAsString(returned.get());
		long currentEpoch = users.findById(fixture.child()).orElseThrow().getGuardianConsentEpoch();
		assertThat(rejected.get()).as("Old generation %s must be rejected after generation %s; observed JSON: %s",
			fixture.epoch(), currentEpoch, returnedJson)
			.isNotNull().extracting(BusinessException::errorCode).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED);
		assertThat(returned.get()).isNull();
		assertSavedHistory(fixture);
		assertThat(jdbc.queryForObject("select active_turn_request_id from learning_sessions where id = ?",
			String.class, fixture.session())).isNull();
		verify(ai, never()).grade(any());
		verify(ai).quizAssessment(any());
		verify(ai).diagnosis(any());
	}

	@ParameterizedTest
	@EnumSource(value = QuizType.class, names = {"MCQ", "OX"})
	void unchangedConsentGenerationStillReturnsCommittedDiagnosisJson(QuizType type) throws Exception {
		Fixture fixture = fixture(type);
		QuizSubmissionService service = new QuizSubmissionService(preparation, grading, persistence, properties,
			pipeline, claims, access, fence);

		QuizSubmitResponse response = service.submit(fixture.child(), fixture.quiz(), request(type));

		assertThat(response.passed()).isFalse();
		assertThat(response.uiActions()).singleElement().satisfies(action -> {
			assertThat(action.type()).isEqualTo("DIAGNOSIS_QUESTION");
			assertThat(action.content()).isEqualTo(DIAGNOSTIC_PROMPT);
		});
		assertThat(new ObjectMapper().writeValueAsString(response)).contains(DIAGNOSTIC_PROMPT);
		assertSavedHistory(fixture);
		verify(ai, never()).grade(any());
		verify(ai).quizAssessment(any());
		verify(ai).diagnosis(any());
	}

	@ParameterizedTest
	@EnumSource(value = QuizType.class, names = {"MCQ", "OX"})
	void declinedOptionalAiKeepsDeterministicSubmissionWithoutAiOutputOrHistory(QuizType type) throws Exception {
		Fixture fixture = fixture(type, false);
		QuizSubmissionService service = new QuizSubmissionService(preparation, grading, persistence, properties,
			pipeline, claims, access, fence);

		QuizSubmitResponse response = service.submit(fixture.child(), fixture.quiz(), request(type));

		assertThat(response.passed()).isFalse();
		assertThat(response.score()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(response.uiActions()).allSatisfy(action -> {
			assertThat(action.type()).isEqualTo("BINARY_DECISION");
			assertThat(action.diagnosisId()).isNull();
		});
		assertThat(new ObjectMapper().writeValueAsString(response)).doesNotContain(DIAGNOSTIC_PROMPT, "DIAGNOSIS_QUESTION");
		assertThat(jdbc.queryForObject("select count(*) from quiz_submissions where quiz_id = ?", Long.class, fixture.quiz())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from quiz_assessments where session_id = ?", Long.class, fixture.session())).isZero();
		assertThat(jdbc.queryForObject("select count(*) from diagnoses where session_id = ?", Long.class, fixture.session())).isZero();
		assertThat(jdbc.queryForObject("select active_turn_request_id from learning_sessions where id = ?",
			String.class, fixture.session())).isNull();
		verifyNoInteractions(ai, usage);
	}

	private Fixture fixture(QuizType type) {
		return fixture(type, true);
	}

	private Fixture fixture(QuizType type, boolean externalAi) {
		return transaction().execute(status -> {
			String suffix = UUID.randomUUID().toString();
			User child = User.create("synthetic-" + suffix + "@example.test", "!synthetic", "Synthetic child");
			child.recordSignupDateOfBirth(LocalDate.of(2013, 1, 1));
			child.verifyEmail(NOW.minusSeconds(60));
			approve(child, externalAi);
			users.saveAndFlush(child);
			LearningMaterial material = LearningMaterial.create(child, "Synthetic material", "synthetic/" + suffix + ".pdf");
			material.markReady(2);
			materials.saveAndFlush(material);
			LearningSession session = sessions.saveAndFlush(LearningSession.create(child, material));
			List<QuizOption> choices = type == QuizType.MCQ
				? List.of(new QuizOption("correct", "Synthetic correct"), new QuizOption("wrong", "Synthetic wrong")) : null;
			Quiz quiz = quizzes.saveAndFlush(Quiz.create(session, 1, "Synthetic quiz", 1, 1, type,
				List.of(new PublicQuizQuestion("q1", "Synthetic question", BigDecimal.TEN, choices)),
				List.of(new PrivateQuizQuestion("q1", type == QuizType.MCQ ? "correct" : null,
					type == QuizType.OX ? Boolean.TRUE : null, "Synthetic explanation", null, List.of(), List.of(), null)), "1.0"));
			session.activateQuiz(quiz.getId(), List.of());
			sessions.flush();
			return new Fixture(child.getId(), session.getId(), quiz.getId(), child.getGuardianConsentEpoch());
		});
	}

	private void approve(User user, boolean externalAi) {
		user.recordGuardianTeamApproval(NOW.plusSeconds(3600), externalAi);
		user.recordGuardianTeamPolicyDigest(policy.configurationDigest());
	}

	private QuizSubmitRequest request(QuizType type) {
		return new QuizSubmitRequest("synthetic-request", List.of(new QuizSubmitRequest.Answer("q1",
			StringNode.valueOf(type == QuizType.MCQ ? "wrong" : "false"))));
	}

	private void assertSavedHistory(Fixture fixture) {
		assertThat(jdbc.queryForObject("select count(*) from quiz_submissions where quiz_id = ?", Long.class, fixture.quiz())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from quiz_assessments where session_id = ?", Long.class, fixture.session())).isEqualTo(1);
		assertThat(jdbc.queryForObject("select diagnostic_prompt from diagnoses where session_id = ?", String.class,
			fixture.session())).isEqualTo(DIAGNOSTIC_PROMPT);
	}

	private TransactionTemplate transaction() {
		return new TransactionTemplate(transactions);
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		}
	}

	private record Fixture(Long child, Long session, Long quiz, long epoch) { }
}
