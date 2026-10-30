package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.Hibernate;
import org.hibernate.LockMode;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.mail.EmailService;
import io.edupilot.user.*;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** Actual JPA, transactions and security over disposable synthetic data; no external mail, AI, SMS or permissions. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-team;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-team", "edupilot.mail.enabled=false",
	"edupilot.guardian.web.enabled=false", "edupilot.guardian.team.enabled=true", "edupilot.guardian.team.policy-confirmed=true",
	"edupilot.guardian.team.portal-base-url=https://guardian.example.invalid",
	"edupilot.guardian.team.notice-version=synthetic-v1",
	"edupilot.guardian.team.notice-digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	"edupilot.guardian.team.notice-url=https://guardian.example.invalid/notice",
	"edupilot.guardian.team.collection-items-text=보호자 연락처와 확인 결과",
	"edupilot.guardian.team.purposes-text=합성 테스트의 보호자 확인",
	"edupilot.guardian.team.retention-text=합성 테스트에서 확인된 보유 안내",
	"edupilot.guardian.team.refusal-text=동의를 거부하면 보호자 확인이 필요한 서비스를 이용할 수 없습니다.",
	"edupilot.guardian.team.reply-contact=reply@example.invalid", "edupilot.guardian.team.reply-channel=EMAIL_REPLY",
	"edupilot.guardian.team.reviewer-ids=1", "edupilot.guardian.team.required-scopes=SERVICE",
	"edupilot.guardian.team.optional-ai-scope=EXTERNAL_AI", "edupilot.guardian.team.optional-consent-text=외부 AI 전송 동의는 선택입니다.",
	"edupilot.guardian.team.link-ttl=30m", "edupilot.guardian.team.request-ttl=5d",
	"edupilot.guardian.team.approved-evidence-retention=30d", "edupilot.guardian.team.approval-validity=7d",
	"edupilot.guardian.team.prior-consent.enabled=true", "edupilot.guardian.team.prior-consent.policy-confirmed=true",
	"edupilot.guardian.team.prior-consent.methods=IN_PERSON",
	"edupilot.guardian.team.prior-consent.policy-reference=synthetic-reviewed-policy", "edupilot.guardian.team.prior-consent.max-age=30d",
	"edupilot.guardian.team.recovery-initial-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
class GuardianTeamJpaTest {
	@Autowired GuardianTeamService service;
	@Autowired GuardianTeamRequestRepository requests;
	@Autowired GuardianTeamEventRepository events;
	@Autowired GuardianTeamOperationRepository operations;
	@Autowired UserRepository users;
	@Autowired GuardianTeamProperties policy;
	@Autowired UserService userService;
	@Autowired PasswordEncoder passwords;
	@Autowired JwtTokenProvider tokens;
	@Autowired WebApplicationContext context;
	@Autowired ObjectMapper json;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired EntityManagerFactory entityManagerFactory;
	@PersistenceContext EntityManager entityManager;
	@Autowired io.edupilot.deletion.DeletionJournalLockRepository deletionLocks;
	@MockitoBean Clock clock;
	@MockitoBean AiClient ai;
	@MockitoBean EmailService mail;
	private Instant baseline;
	private User reviewer;
	private MockMvc mvc;

	@BeforeEach void setup() {
		baseline = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
		when(clock.instant()).thenReturn(baseline); when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		jdbc.update("delete from guardian_team_mail_bindings"); operations.deleteAll(); events.deleteAll(); requests.deleteAll();
		reviewer = users.findById(1L).orElseGet(() -> users.saveAndFlush(actor(UserRole.ADMIN, LocalDate.of(1980, 1, 1))));
		reviewer.reinstate(); reviewer.changeRole(UserRole.ADMIN); reviewer.verifyEmail(baseline); users.saveAndFlush(reviewer);
		assertThat(reviewer.getId()).isEqualTo(1L);
		if (!deletionLocks.existsById(1)) { deletionLocks.saveAndFlush(io.edupilot.deletion.DeletionJournalLock.initial()); }
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test void entryIsAuthenticatedButAnUnverifiedChildCanCheckItWithoutCreatingACase() throws Exception {
		mvc.perform(get("/api/users/me/guardian-requests/entry")).andExpect(status().isUnauthorized());
		User child = unverifiedChild();
		mvc.perform(get("/api/users/me/guardian-requests/entry").header("Authorization", bearer(child)))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(jsonPath("$.data.requirement").value("REQUIRED"))
			.andExpect(jsonPath("$.data.teamReviewAvailable").value(true))
			.andExpect(jsonPath("$.data.canStartRequest").value(true))
			.andExpect(jsonPath("$.data.replyChannel").value("EMAIL_REPLY"))
			.andExpect(jsonPath("$.data.request").isEmpty())
			.andExpect(jsonPath("$.data.dateOfBirth").doesNotExist())
			.andExpect(jsonPath("$.data.userId").doesNotExist());
		mvc.perform(get("/api/materials").header("Authorization", bearer(child)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		assertThat(requests.count()).isZero(); assertThat(events.count()).isZero(); assertThat(operations.count()).isZero();
		verifyNoInteractions(ai, mail);
	}

	@Test void entryUsesTheCurrentKstYearAndPreservesLegacyAndUnknownDobBoundaries() {
		User boundary = users.saveAndFlush(actor(UserRole.LEARNER, LocalDate.of(2012, 1, 1)));
		when(clock.instant()).thenReturn(Instant.parse("2026-12-31T14:59:59Z"));
		assertThat(service.entry(boundary.getId()).requirement()).isEqualTo(GuardianTeamDtos.Requirement.REQUIRED);
		when(clock.instant()).thenReturn(Instant.parse("2026-12-31T15:00:00Z"));
		var adult = service.entry(boundary.getId());
		assertThat(adult.requirement()).isEqualTo(GuardianTeamDtos.Requirement.NOT_REQUIRED);
		assertThat(adult.canStartRequest()).isFalse();
		User unknown = users.saveAndFlush(User.create("unknown-" + UUID.randomUUID() + "@example.invalid", "!synthetic", "합성 계정"));
		var missing = service.entry(unknown.getId());
		assertThat(missing.requirement()).isEqualTo(GuardianTeamDtos.Requirement.BIRTHDATE_REQUIRED);
		assertThat(missing.canStartRequest()).isFalse();
		User future = users.saveAndFlush(actor(UserRole.LEARNER, LocalDate.of(2099, 1, 1)));
		assertThat(service.entry(future.getId()).requirement()).isEqualTo(GuardianTeamDtos.Requirement.BIRTHDATE_REQUIRED);
		jdbc.update("update users set access_cohort='LEGACY_EXEMPT' where id=?", unknown.getId());
		assertThat(service.entry(unknown.getId()).requirement()).isEqualTo(GuardianTeamDtos.Requirement.NOT_REQUIRED);
		assertThat(requests.count()).isZero(); verifyNoInteractions(ai, mail);
	}

	@Test void entryResumesPendingAndApprovedCasesAndOnlyOffersANewCaseAfterExpiry() {
		User child = child(); var first = intake(child, false);
		var pending = service.entry(child.getId());
		assertThat(pending.canStartRequest()).isFalse();
		assertThat(pending.request().status()).isEqualTo(first.status());
		var approved = approve(confirm(first.status(), Set.of("SERVICE")), "entry-approve");
		var resumed = service.entry(child.getId());
		assertThat(resumed.canStartRequest()).isFalse();
		assertThat(resumed.request().status()).isEqualTo(approved);
		assertThat(resumed.request().status().serviceApproved()).isTrue();
		assertThat(resumed.request().status().externalAiApproved()).isFalse();
		when(clock.instant()).thenReturn(approved.approvedUntil());
		var expired = service.entry(child.getId());
		assertThat(expired.canStartRequest()).isTrue();
		assertThat(expired.request().status().state()).isEqualTo(GuardianTeamRequest.State.EXPIRED);
		assertThat(expired.request().status().serviceApproved()).isFalse();
		assertThat(expired.request().status().generation()).isEqualTo(first.status().generation());
		assertThat(requests.count()).isEqualTo(1); verifyNoInteractions(ai, mail);
	}

	@Test void structuredReplyAndDeclarationFieldsAreVisibleOnlyInTheAppropriateResponses() throws Exception {
		User child = child(); var first = intake(child, false); var issued = link(child, first.status());
		assertThat(first.replyChannel()).isEqualTo("EMAIL_REPLY");
		String raw = issued.url().split("#token=", 2)[1];
		mvc.perform(post("/api/auth/guardian-team/view").contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(new GuardianTeamDtos.Token(raw))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.replyChannel").value("EMAIL_REPLY"))
			.andExpect(jsonPath("$.data.generationStartedAt").doesNotExist())
			.andExpect(jsonPath("$.data.declaredScopes").doesNotExist()).andExpect(jsonPath("$.data.userId").doesNotExist());
		var empty = service.detail(1L, issued.status().requestId());
		assertThat(empty.generationStartedAt()).isEqualTo(baseline); assertThat(empty.declaredScopes()).isEmpty();
		assertThat(empty.requiredScopes()).containsExactly("SERVICE"); assertThat(empty.optionalAiScope()).isEqualTo("EXTERNAL_AI");
		assertThat(empty.status().generation()).isEqualTo(issued.status().generation());
		assertThat(empty.status().revision()).isEqualTo(issued.status().revision());
		var declaration = service.consent(consent(raw, issued.status(), "structured-declare", Set.of("SERVICE", "EXTERNAL_AI")), ip());
		mvc.perform(get("/api/admin/guardian-requests/" + declaration.requestId()).header("Authorization", bearer(reviewer)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.replyChannel").value("EMAIL_REPLY"))
			.andExpect(jsonPath("$.data.generationStartedAt").value(baseline.toString()))
			.andExpect(jsonPath("$.data.declaredScopes", org.hamcrest.Matchers.containsInAnyOrder("SERVICE", "EXTERNAL_AI")))
			.andExpect(jsonPath("$.data.requiredScopes", org.hamcrest.Matchers.contains("SERVICE")))
			.andExpect(jsonPath("$.data.optionalAiScope").value("EXTERNAL_AI"))
			.andExpect(jsonPath("$.data.status.state").value("DECLARED"))
			.andExpect(jsonPath("$.data.status.serviceApproved").value(false))
			.andExpect(jsonPath("$.data.status.externalAiApproved").value(false));
		assertError(() -> confirm(declaration, Set.of("SERVICE")), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertThat(service.detail(1L, declaration.requestId()).declaredScopes()).containsExactlyInAnyOrder("SERVICE", "EXTERNAL_AI");
		verifyNoInteractions(ai, mail);
	}

	@Test void reissueAndWithdrawalClearStructuredDeclarationsAndANewGenerationGetsANewStart() {
		User child = child(); var first = intake(child, false); var issued = link(child, first.status());
		var declared = service.consent(consent(issued.url().split("#token=", 2)[1], issued.status(), "before-reissue", Set.of("SERVICE")), ip());
		var reissued = service.issueLink(child.getId(), declared.requestId(), new GuardianTeamDtos.Mutation("fresh-link", declared.generation(), declared.revision()));
		var detail = service.detail(1L, reissued.status().requestId());
		assertThat(detail.declaredScopes()).isEmpty(); assertThat(detail.status().webDeclaredAt()).isNull();
		assertThat(detail.generationStartedAt()).isEqualTo(baseline);
		var again = service.consent(consent(reissued.url().split("#token=", 2)[1], reissued.status(), "after-reissue", Set.of("SERVICE")), ip());
		service.withdrawRequest(child.getId(), again.requestId(), new GuardianTeamDtos.Mutation("entry-withdraw", again.generation(), again.revision()));
		assertThat(service.detail(1L, again.requestId()).declaredScopes()).isEmpty();
		when(clock.instant()).thenReturn(baseline.plusSeconds(60));
		var next = service.intake(child.getId(), new GuardianTeamDtos.Intake("new-entry-generation", null, null, false));
		var nextDetail = service.detail(1L, next.status().requestId());
		assertThat(nextDetail.generationStartedAt()).isEqualTo(baseline.plusSeconds(60));
		assertThat(nextDetail.status().generation()).isEqualTo(first.status().generation() + 1);
		assertThat(nextDetail.declaredScopes()).isEmpty(); verifyNoInteractions(ai, mail);
	}

	@Test void aChangedPolicyDoesNotAdvertiseItsReplyMethodAsBelongingToAnOldCase() throws Exception {
		User child = child(); var first = intake(child, false);
		jdbc.update("update guardian_team_requests set configuration_digest=? where id=?", "b".repeat(64), first.status().requestId());
		var view = service.self(child.getId()); var detail = service.detail(1L, first.status().requestId());
		assertThat(view.status().currentNotice()).isFalse(); assertThat(view.replyChannel()).isNull(); assertThat(view.forms()).isEmpty();
		assertThat(detail.replyChannel()).isNull(); assertThat(detail.forms()).isEmpty();
		assertThat(view.requiredScopes()).isEmpty(); assertThat(view.optionalAiScope()).isNull();
		assertThat(detail.requiredScopes()).isEmpty(); assertThat(detail.optionalAiScope()).isNull();
		mvc.perform(get("/api/admin/guardian-requests/" + first.status().requestId()).header("Authorization", bearer(reviewer)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status.currentNotice").value(false))
			.andExpect(jsonPath("$.data.requiredScopes").isEmpty())
			.andExpect(jsonPath("$.data.optionalAiScope").value(org.hamcrest.Matchers.nullValue()));
		assertThat(service.entry(child.getId()).canStartRequest()).isFalse(); verifyNoInteractions(ai, mail);
	}

	@Test void anAvailableAiOptionCannotExpandTheActualWebDeclaration() {
		User child = child(); var first = intake(child, false); var issued = link(child, first.status());
		var declared = service.consent(consent(issued.url().split("#token=", 2)[1], issued.status(), "service-only-declaration", Set.of("SERVICE")), ip());
		var detail = service.detail(1L, declared.requestId());
		assertThat(detail.optionalAiScope()).isEqualTo("EXTERNAL_AI"); assertThat(detail.declaredScopes()).containsExactly("SERVICE");
		assertThat(detail.status().serviceApproved()).isFalse(); assertThat(detail.status().externalAiApproved()).isFalse();
		assertError(() -> confirm(declared, Set.of("SERVICE", "EXTERNAL_AI")), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertThat(service.detail(1L, declared.requestId()).declaredScopes()).containsExactly("SERVICE");
		verifyNoInteractions(ai, mail);
	}

	@Test void currentPolicyOptionsCannotMakeAnExpiredRequestWritableOrApproved() {
		User child = child(); var first = intake(child, false);
		when(clock.instant()).thenReturn(baseline.plus(Duration.ofDays(5)));
		var detail = service.detail(1L, first.status().requestId());
		assertThat(detail.status().state()).isEqualTo(GuardianTeamRequest.State.EXPIRED);
		assertThat(detail.status().currentNotice()).isTrue(); assertThat(detail.requiredScopes()).containsExactly("SERVICE");
		assertThat(detail.optionalAiScope()).isEqualTo("EXTERNAL_AI");
		assertThat(detail.status().serviceApproved()).isFalse(); assertThat(detail.status().externalAiApproved()).isFalse();
		assertError(() -> confirm(detail.status(), Set.of("SERVICE", "EXTERNAL_AI")), ErrorCode.GUARDIAN_STATE_CONFLICT);
		verifyNoInteractions(ai, mail);
	}

	@Test void webDeclarationAndExplicitResponseRemainSeparateFromHumanApproval() {
		User child = child(); var intake = intake(child, false); var link = link(child, intake.status());
		String raw = link.url().split("#token=", 2)[1];
		var declaration = service.consent(consent(raw, link.status(), "consent-1", Set.of("SERVICE", "EXTERNAL_AI")), ip());
		assertThat(declaration.state()).isEqualTo(GuardianTeamRequest.State.DECLARED);
		assertThat(declaration.explicitResponseAt()).isNull(); assertThat(declaration.serviceApproved()).isFalse();
		assertThat(users.findById(child.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.MANUAL_PENDING);
		var confirmed = confirm(declaration, Set.of("SERVICE", "EXTERNAL_AI"));
		assertThat(confirmed.serviceApproved()).isFalse(); assertThat(confirmed.explicitResponseAt()).isEqualTo(baseline);
		var result = approve(confirmed, "approve-1");
		assertThat(result.serviceApproved()).isTrue(); assertThat(result.externalAiApproved()).isTrue();
		User saved = users.findById(child.getId()).orElseThrow();
		assertThat(saved.getAgeVerificationState()).isEqualTo(AgeVerificationState.TEAM_APPROVED);
		assertThat(saved.getGuardianApprovalPolicyDigest()).isEqualTo(policy.configurationDigest());
		assertThat(jdbc.queryForObject("select guardian_contact from guardian_team_requests where id=?", String.class, result.requestId())).isNull();
		assertThat(operations.count()).isEqualTo(1); // Only the approval receipt remains; contact-derived intake digests are gone.
		verifyNoInteractions(ai, mail);
	}

	@Test void serviceApprovalDoesNotInferOptionalExternalAiConsent() {
		User child = child(); var result = approve(confirm(intake(child, false).status(), Set.of("SERVICE")), "approve-1");
		assertThat(result.serviceApproved()).isTrue(); assertThat(result.externalAiApproved()).isFalse();
		assertThat(users.findById(child.getId()).orElseThrow().isGuardianAiConsentAllowed()).isFalse();
	}

	@Test void uncheckedRelationshipOrEmailClickCannotApprove() {
		User child = child(); var intake = intake(child, false);
		assertError(() -> approve(intake.status(), "approve-before-response"), ErrorCode.GUARDIAN_STATE_CONFLICT);
		var confirmed = confirm(intake.status(), Set.of("SERVICE"));
		assertError(() -> service.decide(1L, confirmed.requestId(), new GuardianTeamDtos.Decision("approve-unchecked",
			confirmed.generation(), confirmed.revision(), GuardianTeamDtos.DecisionKind.APPROVE, null, false, true, true, true)), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianApprovedUntil()).isNull();
	}

	@Test void currentDbReviewerRoleAndExplicitDesignationAreRechecked() {
		var pending = confirm(intake(child(), false).status(), Set.of("SERVICE"));
		User otherAdmin = users.saveAndFlush(actor(UserRole.ADMIN, LocalDate.of(1980, 1, 1)));
		assertError(() -> service.detail(otherAdmin.getId(), pending.requestId()), ErrorCode.ACCESS_DENIED);
		reviewer.changeRole(UserRole.LEARNER); users.saveAndFlush(reviewer);
		assertError(() -> approve(pending, "approve-demoted"), ErrorCode.ACCESS_DENIED);
		reviewer.changeRole(UserRole.ADMIN); reviewer.suspend("합성 권한 회수", 1L, baseline); users.saveAndFlush(reviewer);
		assertError(() -> approve(pending, "approve-suspended"), ErrorCode.ACCESS_DENIED);
	}

	@Test void identicalIntakeRetriesSerializeAndConflictingKeysCannotChangeThePayload() throws Exception {
		User child = child(); var body = new GuardianTeamDtos.Intake("same-intake", null, null, false);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(6)) {
			var calls = new ArrayList<Future<GuardianTeamDtos.View>>();
			for (int i = 0; i < 6; i++) { calls.add(executor.submit(() -> { start.await(); return service.intake(child.getId(), body); })); }
			start.countDown(); String id = calls.getFirst().get(15, TimeUnit.SECONDS).status().requestId();
			for (var call : calls) { assertThat(call.get(15, TimeUnit.SECONDS).status().requestId()).isEqualTo(id); }
		} finally { start.countDown(); }
		assertThat(requests.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(1);
		assertError(() -> service.intake(child.getId(), new GuardianTeamDtos.Intake("same-intake", "합성 보호자", "guardian@example.invalid", true)), ErrorCode.GUARDIAN_STATE_CONFLICT);
	}

	@Test void concurrentIdenticalApprovalCommitsOneEventAndOneEpochChange() throws Exception {
		User child = child(); var pending = confirm(intake(child, false).status(), Set.of("SERVICE"));
		long before = users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(4)) {
			var calls = new ArrayList<Future<GuardianTeamDtos.Status>>();
			for (int i = 0; i < 4; i++) { calls.add(executor.submit(() -> { start.await(); return approve(pending, "same-approve"); })); }
			start.countDown(); for (var call : calls) { assertThat(call.get(15, TimeUnit.SECONDS).state()).isEqualTo(GuardianTeamRequest.State.APPROVED); }
		} finally { start.countDown(); }
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch()).isEqualTo(before + 1);
		assertThat(events.findByRequestIdOrderByRecordedAtAscIdAsc(pending.requestId(), org.springframework.data.domain.PageRequest.of(0, 100)))
			.filteredOn(event -> event.eventType().equals("APPROVE")).hasSize(1);
	}

	@Test void concurrentApproveAndRejectAcceptOnlyOneCurrentRevision() throws Exception {
		User child = child(); var pending = confirm(intake(child, false).status(), Set.of("SERVICE"));
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			Future<Boolean> approval = executor.submit(() -> { start.await(); return tryDecision(() -> approve(pending, "race-approve")); });
			Future<Boolean> rejection = executor.submit(() -> { start.await(); return tryDecision(() -> service.decide(1L, pending.requestId(),
				new GuardianTeamDtos.Decision("race-reject", pending.generation(), pending.revision(), GuardianTeamDtos.DecisionKind.REJECT,
					GuardianTeamRequest.Reason.RELATIONSHIP_UNCONFIRMED, false, false, false, false))); });
			start.countDown(); assertThat(approval.get(15, TimeUnit.SECONDS) ^ rejection.get(15, TimeUnit.SECONDS)).isTrue();
		} finally { start.countDown(); }
		var state = service.self(child.getId()).status();
		assertThat(state.state()).isIn(GuardianTeamRequest.State.APPROVED, GuardianTeamRequest.State.REJECTED);
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianApprovedUntil() != null).isEqualTo(state.state() == GuardianTeamRequest.State.APPROVED);
	}

	@Test void reissueKeepsFirstCollectionDeadlineAndOldTokenCannotBeUsed() {
		User child = child(); var intake = intake(child, true); var first = link(child, intake.status());
		String old = first.url().split("#token=", 2)[1]; when(clock.instant()).thenReturn(baseline.plus(Duration.ofDays(1)));
		var second = service.issueLink(child.getId(), first.status().requestId(), new GuardianTeamDtos.Mutation("second-link", first.status().generation(), first.status().revision()));
		assertThat(second.status().contactEraseDueAt()).isEqualTo(baseline.plus(Duration.ofDays(5)));
		assertError(() -> service.publicView(new GuardianTeamDtos.Token(old), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
		assertError(() -> service.publicView(new GuardianTeamDtos.Token("X".repeat(43)), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
		when(clock.instant()).thenReturn(baseline.plus(Duration.ofDays(1)).plusSeconds(1801));
		assertError(() -> service.publicView(new GuardianTeamDtos.Token(second.url().split("#token=", 2)[1]), ip()), ErrorCode.GUARDIAN_LINK_INVALID);
	}

	@Test void declarationRetryIsIdempotentOnlyForTheSameUnexpiredTokenAndPayload() {
		User child = child(); var issued = link(child, intake(child, false).status());
		String raw = issued.url().split("#token=", 2)[1];
		var body = consent(raw, issued.status(), "consent-retry", Set.of("SERVICE"));
		var first = service.consent(body, ip()); var second = service.consent(body, ip());
		assertThat(second.revision()).isEqualTo(first.revision());
		var changed = new GuardianTeamDtos.Consent(raw, body.idempotencyKey(), body.generation(), body.revision(), body.noticeVersion(), body.noticeDigest(),
			true, true, GuardianTeamRequest.Relationship.MINOR_GUARDIAN, Set.of("SERVICE"), "합성 보호자", "guardian@example.invalid");
		assertError(() -> service.consent(changed, ip()), ErrorCode.GUARDIAN_STATE_CONFLICT);
		when(clock.instant()).thenReturn(baseline.plusSeconds(1801));
		assertError(() -> service.consent(body, ip()), ErrorCode.GUARDIAN_LINK_INVALID);
	}

	@Test void needsInformationNeverExtendsTheOriginalDeadlineOrPreservesAConfirmation() {
		User child = child(); var pending = confirm(intake(child, true).status(), Set.of("SERVICE"));
		var needs = service.decide(1L, pending.requestId(), new GuardianTeamDtos.Decision("needs", pending.generation(), pending.revision(),
			GuardianTeamDtos.DecisionKind.NEEDS_INFORMATION, GuardianTeamRequest.Reason.INCOMPLETE_RESPONSE, false, false, false, false));
		assertThat(needs.state()).isEqualTo(GuardianTeamRequest.State.NEEDS_INFORMATION); assertThat(needs.explicitResponseAt()).isNull();
		assertThat(needs.contactEraseDueAt()).isEqualTo(baseline.plus(Duration.ofDays(5)));
		assertThat(service.self(child.getId()).forms()).containsKeys("needs_information", "consent_email", "reply_form").doesNotContainKey("approved");
	}

	@Test void recoveryAfterRestartErasesUnconfirmedContactAndBlocksLateApproval() {
		User child = child(); var pending = confirm(intake(child, true).status(), Set.of("SERVICE"));
		when(clock.instant()).thenReturn(baseline.plus(Duration.ofDays(5)));
		assertError(() -> approve(pending, "late-approval"), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertThat(service.recoveryIds()).contains(pending.requestId()); service.recover(pending.requestId()); service.recover(pending.requestId());
		var saved = requests.findById(pending.requestId()).orElseThrow();
		assertThat(saved.state()).isEqualTo(GuardianTeamRequest.State.EXPIRED);
		assertThat(saved.guardianContact()).isNull(); assertThat(saved.guardianName()).isNull(); assertThat(saved.evidenceReference()).isNull();
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianApprovedUntil()).isNull();
	}

	@Test void rejectImmediatelyErasesContactAndReapprovalRequiresANewGeneration() {
		User child = child(); var first = intake(child, true); var pending = first.status();
		assertThat(first.forms().get("reply_form")).contains("신청 차수: " + pending.generation());
		var rejected = service.decide(1L, pending.requestId(), new GuardianTeamDtos.Decision("reject", pending.generation(), pending.revision(),
			GuardianTeamDtos.DecisionKind.REJECT, GuardianTeamRequest.Reason.CONSENT_DECLINED, false, false, false, false));
		assertThat(requests.findById(rejected.requestId()).orElseThrow().guardianContact()).isNull();
		var nextView = service.intake(child.getId(), new GuardianTeamDtos.Intake("fresh-intake", null, null, false)); var next = nextView.status();
		assertThat(next.generation()).isEqualTo(pending.generation() + 1);
		assertThat(nextView.forms().get("reply_form")).contains("신청 차수: " + next.generation());
		assertError(() -> approve(pending, "old-approval"), ErrorCode.GUARDIAN_STATE_CONFLICT);
	}

	@Test void revocationAndReapprovalAdvanceConsentEpochAndDoNotReuseOldApproval() {
		User child = child(); var approved = approve(confirm(intake(child, false).status(), Set.of("SERVICE", "EXTERNAL_AI")), "approve-1");
		long before = users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch();
		var revoked = service.revoke(1L, approved.requestId(), new GuardianTeamDtos.Revoke("revoke", approved.generation(), approved.revision(), GuardianTeamRequest.Reason.OPERATOR_REVOKED));
		assertThat(revoked.serviceApproved()).isFalse(); assertThat(revoked.externalAiApproved()).isFalse();
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch()).isGreaterThan(before);
		var next = service.intake(child.getId(), new GuardianTeamDtos.Intake("fresh-intake", null, null, false)).status();
		var again = approve(confirm(next, Set.of("SERVICE")), "approve-2");
		assertThat(again.generation()).isEqualTo(approved.generation() + 1);
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch()).isGreaterThan(before + 1);
		assertError(() -> service.revoke(1L, again.requestId(), new GuardianTeamDtos.Revoke("old-revoke", approved.generation(), approved.revision(), GuardianTeamRequest.Reason.OPERATOR_REVOKED)), ErrorCode.GUARDIAN_STATE_CONFLICT);
	}

	@Test void aRestoredFullAuditBudgetCannotPreventSafeRevocation() {
		User child = child(); var approved = approve(confirm(intake(child, false).status(), Set.of("SERVICE")), "approve-1");
		var row = requests.findById(approved.requestId()).orElseThrow();
		long count = events.countByRequestIdAndGeneration(row.id(), row.generation());
		for (long index = count; index < 100; index++) {
			events.save(GuardianTeamEvent.record(row, "RESTORED_SYNTHETIC_EVENT", null, baseline, baseline.plus(Duration.ofDays(30))));
		}
		events.flush();
		var revoked = service.revoke(1L, approved.requestId(), new GuardianTeamDtos.Revoke("quota-safe-revoke", approved.generation(),
			approved.revision(), GuardianTeamRequest.Reason.OPERATOR_REVOKED));
		assertThat(revoked.state()).isEqualTo(GuardianTeamRequest.State.REVOKED); assertThat(revoked.serviceApproved()).isFalse();
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianApprovedUntil()).isNull();
		assertThat(events.countByRequestIdAndGeneration(row.id(), row.generation())).isEqualTo(101);
	}

	@Test void changedNoticeCannotGrantAndMissingExplicitChecklistCannotBeRegistered() {
		var pending = intake(child(), false).status();
		var unchecked = new GuardianTeamDtos.Confirmation("unchecked", pending.generation(), pending.revision(), GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY,
			"opaque-ref", baseline, pending.noticeVersion(), pending.noticeDigest(), GuardianTeamRequest.Relationship.PARENT,
			Set.of("SERVICE"), true, false, true, true, true);
		assertError(() -> service.confirm(1L, pending.requestId(), unchecked), ErrorCode.VALIDATION_FAILED);
		jdbc.update("update guardian_team_requests set configuration_digest=? where id=?", "b".repeat(64), pending.requestId());
		assertError(() -> confirm(pending, Set.of("SERVICE")), ErrorCode.GUARDIAN_TEAM_CONFIGURATION_CHANGED);
	}

	@Test void anUnconfiguredPhoneFallbackCannotReplaceTheApprovedEmailReplyMethod() {
		var pending = intake(child(), false).status();
		var phone = new GuardianTeamDtos.Confirmation("unapproved-phone", pending.generation(), pending.revision(), GuardianTeamRequest.ConfirmationMethod.PHONE,
			"opaque-phone-ref", baseline, pending.noticeVersion(), pending.noticeDigest(), GuardianTeamRequest.Relationship.PARENT,
			Set.of("SERVICE"), true, true, true, true, true);
		assertError(() -> service.confirm(1L, pending.requestId(), phone), ErrorCode.VALIDATION_FAILED);
		assertThat(service.detail(1L, pending.requestId()).confirmationMethod()).isNull();
	}

	@Test void accountWithdrawalAtomicallyErasesTheCaseAndCannotBeUndoneByALateDecision() {
		User child = child(); var pending = confirm(intake(child, true).status(), Set.of("SERVICE"));
		userService.withdraw(child.getId(), "synthetic-password");
		var saved = requests.findById(pending.requestId()).orElseThrow();
		assertThat(saved.state()).isEqualTo(GuardianTeamRequest.State.WITHDRAWN); assertThat(saved.guardianContact()).isNull();
		assertThat(saved.evidenceReference()).isNull(); assertThat(events.count()).isZero(); assertThat(operations.count()).isZero();
		assertError(() -> approve(pending, "late-approval"), ErrorCode.GUARDIAN_STATE_CONFLICT);
	}

	@Test void failedWithdrawalTransactionRestoresTheCaseAndApprovalTogether() {
		User child = child(); var approved = approve(confirm(intake(child, true).status(), Set.of("SERVICE")), "approve-1");
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			User account = users.findByIdForUpdate(child.getId()).orElseThrow(); account.withdraw(); service.cancelOnWithdrawal(child.getId());
			throw new IllegalStateException("합성 rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(requests.findById(approved.requestId()).orElseThrow().state()).isEqualTo(GuardianTeamRequest.State.APPROVED);
		assertThat(users.findById(child.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.TEAM_APPROVED);
	}

	@Test void controllerKeepsTokenPostPublicAndRejectsCrossUserAndForgedAdminAuthority() throws Exception {
		mvc.perform(post("/api/users/me/guardian-requests").contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"new\"}"))
			.andExpect(status().isUnauthorized());
		User child = child(); var view = intake(child, true); var issued = link(child, view.status());
		String token = issued.url().split("#token=", 2)[1];
		mvc.perform(post("/api/auth/guardian-team/view").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new GuardianTeamDtos.Token(token))))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(jsonPath("$.data.status.state").value("AWAITING_CONSENT"))
			.andExpect(jsonPath("$.data.forms.approved").doesNotExist()).andExpect(jsonPath("$.data.forms.reviewer_checklist").doesNotExist())
			.andExpect(jsonPath("$.data.guardianContact").doesNotExist());
		User other = child();
		mvc.perform(post("/api/users/me/guardian-requests/" + view.status().requestId() + "/link")
			.header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(new GuardianTeamDtos.Mutation("cross", view.status().generation(), view.status().revision()))))
			.andExpect(status().isNotFound());
		String staleAdminToken = bearer(reviewer); reviewer.changeRole(UserRole.LEARNER); users.saveAndFlush(reviewer);
		mvc.perform(get("/api/admin/guardian-requests/" + view.status().requestId()).header("Authorization", staleAdminToken))
			.andExpect(status().isForbidden());
		mvc.perform(get("/api/auth/guardian-team/consent")).andExpect(status().isMethodNotAllowed());
	}

	@Test void anEmailUnverifiedPendingChildCanCancelOnlyTheirOwnRequestWhileBusinessRemainsBlocked() throws Exception {
		User child = unverifiedChild(); assertThat(child.isEmailVerified()).isFalse();
		mvc.perform(post("/api/users/me/guardian-requests").header("Authorization", bearer(child))
			.contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"pending-api\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status.serviceApproved").value(false));
		var pending = service.self(child.getId()).status();
		mvc.perform(get("/api/materials").header("Authorization", bearer(child)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		User other = unverifiedChild();
		mvc.perform(post("/api/users/me/guardian-requests").header("Authorization", bearer(other))
			.contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"missing-origin\",\"guardianContact\":\"guardian@example.invalid\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		var body = new GuardianTeamDtos.Mutation("self-cancel", pending.generation(), pending.revision());
		mvc.perform(post("/api/users/me/guardian-requests/" + pending.requestId() + "/withdraw")
			.header("Authorization", bearer(other)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isNotFound());
		mvc.perform(post("/api/users/me/guardian-requests/" + pending.requestId() + "/withdraw")
			.header("Authorization", bearer(child)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("REVOKED"))
			.andExpect(jsonPath("$.data.serviceApproved").value(false));
		mvc.perform(get("/api/materials").header("Authorization", bearer(child)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
	}

	@ParameterizedTest(name = "locked User proxy current hydration / {0}")
	@EnumSource(value = LockModeType.class, names = {"PESSIMISTIC_READ", "PESSIMISTIC_WRITE"})
	void aLockedUserProxyKeepsItsImplementationAndCurrentGuardianState(LockModeType requested) {
		User child = child(); var approved = approve(confirm(intake(child, false).status(), Set.of("SERVICE", "EXTERNAL_AI")), "proxy-approve");
		try (var pool = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactions).executeWithoutResult(tx -> {
				User proxy = entityManager.getReference(User.class, child.getId());
				assertThat(proxy).isInstanceOf(HibernateProxy.class); assertThat(Hibernate.isInitialized(proxy)).isFalse();
				long previousEpoch = proxy.getGuardianConsentEpoch();
				assertThat(proxy.getGuardianApprovedUntil()).isEqualTo(approved.approvedUntil());
				var session = entityManager.unwrap(SessionImplementor.class);
				var initializer = HibernateProxy.extractLazyInitializer(proxy);
				User implementation = (User) initializer.getImplementation(session);
				assertThat(initializer.getSession()).isSameAs(session); assertThat(implementation).isNotNull();
				try {
					pool.submit(() -> service.revoke(1L, approved.requestId(), new GuardianTeamDtos.Revoke("proxy-revoke",
						approved.generation(), approved.revision(), GuardianTeamRequest.Reason.OPERATOR_REVOKED))).get(5, TimeUnit.SECONDS);
				} catch (Exception failure) { throw new IllegalStateException(failure); }
				assertThat(proxy.getGuardianConsentEpoch()).isEqualTo(previousEpoch);
				if (requested == LockModeType.PESSIMISTIC_WRITE) { proxy.updateProfile("합성 프록시 수정 보존", "합성 기관"); }
				User locked = requested == LockModeType.PESSIMISTIC_WRITE
					? users.findByIdForUpdate(child.getId()).orElseThrow() : users.findByIdForBusinessAccess(child.getId()).orElseThrow();
				assertThat(locked).isSameAs(proxy);
				var context = session.getPersistenceContextInternal();
				LockMode expected = requested == LockModeType.PESSIMISTIC_WRITE ? LockMode.WRITE : LockMode.PESSIMISTIC_READ;
				assertThat(context.getEntry(proxy)).isNull(); assertThat(context.getEntry(implementation).getLockMode()).isEqualTo(expected);
				System.out.println("SYNTHETIC_USER_PROXY requested=" + requested + " rawEntry=null managedMode=" + expected + " staleEpoch=" + previousEpoch);
				UserCurrentStateRefresh.refreshLocked(entityManager, locked, requested);
				assertThat(initializer.getImplementation(session)).isSameAs(implementation);
				assertThat(initializer.getImplementation()).isSameAs(implementation);
				assertThat(entityManager.contains(proxy)).isTrue(); assertThat(entityManager.contains(implementation)).isTrue();
				assertThat(context.getEntry(implementation).getLockMode()).isEqualTo(expected);
				assertThat(proxy.getGuardianConsentEpoch()).isEqualTo(previousEpoch + 1);
				assertThat(proxy.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
				assertThat(proxy.getGuardianApprovedUntil()).isNull(); assertThat(proxy.isGuardianAiConsentAllowed()).isFalse();
				assertThat(proxy.getGuardianApprovalPolicyDigest()).isNull();
				if (requested == LockModeType.PESSIMISTIC_WRITE) {
					assertThat(proxy.getName()).isEqualTo("합성 프록시 수정 보존"); assertThat(proxy.getAffiliation()).isEqualTo("합성 기관");
				}
			});
		}
		User stored = users.findById(child.getId()).orElseThrow();
		assertThat(stored.getGuardianApprovedUntil()).isNull(); assertThat(stored.isGuardianAiConsentAllowed()).isFalse();
		if (requested == LockModeType.PESSIMISTIC_WRITE) { assertThat(stored.getName()).isEqualTo("합성 프록시 수정 보존"); }
		verifyNoInteractions(ai, mail);
	}

	@Test void anUnloadedProxyCannotAcquireALockOrInitializeThroughCurrentStateRefresh() {
		User child = child();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			User proxy = entityManager.getReference(User.class, child.getId());
			var initializer = HibernateProxy.extractLazyInitializer(proxy); var session = entityManager.unwrap(SessionImplementor.class);
			assertThat(initializer).isNotNull(); assertThat(Hibernate.isInitialized(proxy)).isFalse();
			assertThat(initializer.getImplementation(session)).isNull();
			assertThatThrownBy(() -> UserCurrentStateRefresh.refreshLocked(entityManager, proxy, LockModeType.PESSIMISTIC_READ))
				.isInstanceOf(IllegalStateException.class);
			assertThat(Hibernate.isInitialized(proxy)).isFalse(); assertThat(initializer.getImplementation(session)).isNull();
		});
	}

	@Test void aDetachedProxyCannotReuseAnotherManagedUsersSameIdLock() {
		User child = child();
		User detached = new TransactionTemplate(transactions).execute(tx -> entityManager.getReference(User.class, child.getId()));
		var initializer = HibernateProxy.extractLazyInitializer(detached);
		assertThat(initializer).isNotNull(); assertThat(initializer.getSession()).isNull(); assertThat(Hibernate.isInitialized(detached)).isFalse();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			User locked = users.findByIdForUpdate(child.getId()).orElseThrow();
			locked.updateProfile("합성 분리 세션 수정 보존", null);
			assertThatThrownBy(() -> UserCurrentStateRefresh.refreshLocked(entityManager, detached, LockModeType.PESSIMISTIC_READ))
				.isInstanceOf(IllegalStateException.class);
			assertThat(initializer.getSession()).isNull(); assertThat(Hibernate.isInitialized(detached)).isFalse();
			assertThat(entityManager.contains(locked)).isTrue(); assertThat(entityManager.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		});
		assertThat(users.findById(child.getId()).orElseThrow().getName()).isEqualTo("합성 분리 세션 수정 보존");
	}

	@Test void aForeignLiveSessionProxyCannotReuseTheCurrentSessionsUserLock() {
		User child = child();
		try (var foreign = entityManagerFactory.createEntityManager()) {
			foreign.getTransaction().begin();
			try {
				User proxy = foreign.getReference(User.class, child.getId()); var initializer = HibernateProxy.extractLazyInitializer(proxy);
				assertThat(initializer).isNotNull(); assertThat(Hibernate.isInitialized(proxy)).isFalse();
				new TransactionTemplate(transactions).executeWithoutResult(tx -> {
					var session = entityManager.unwrap(SessionImplementor.class);
					User locked = users.findByIdForUpdate(child.getId()).orElseThrow(); locked.updateProfile("합성 다른 세션 수정 보존", null);
					assertThat(initializer.getSession()).isNotSameAs(session);
					assertThatThrownBy(() -> UserCurrentStateRefresh.refreshLocked(entityManager, proxy, LockModeType.PESSIMISTIC_READ))
						.isInstanceOf(IllegalStateException.class);
					assertThat(Hibernate.isInitialized(proxy)).isFalse(); assertThat(entityManager.contains(locked)).isTrue();
					assertThat(entityManager.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
				});
			} finally { foreign.getTransaction().rollback(); }
		}
		assertThat(users.findById(child.getId()).orElseThrow().getName()).isEqualTo("합성 다른 세션 수정 보존");
	}

	@Test void aReadLockedUserProxyCannotRequestAnUnacquiredWriteLock() {
		User child = child();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			User proxy = entityManager.getReference(User.class, child.getId());
			assertThat(proxy).isInstanceOf(HibernateProxy.class);
			assertThat(users.findByIdForBusinessAccess(child.getId()).orElseThrow()).isSameAs(proxy);
			var session = entityManager.unwrap(SessionImplementor.class);
			User implementation = (User) HibernateProxy.extractLazyInitializer(proxy).getImplementation(session);
			assertThat(implementation).isNotNull();
			assertThat(session.getPersistenceContextInternal().getEntry(implementation).getLockMode()).isEqualTo(LockMode.PESSIMISTIC_READ);
			assertThatThrownBy(() -> UserCurrentStateRefresh.refreshLocked(entityManager, proxy, LockModeType.PESSIMISTIC_WRITE))
				.isInstanceOf(IllegalStateException.class);
			assertThat(session.getPersistenceContextInternal().getEntry(implementation).getLockMode()).isEqualTo(LockMode.PESSIMISTIC_READ);
			assertThat(entityManager.contains(implementation)).isTrue();
		});
	}

	@Test void priorConsentOptionsExposeOnlyReviewedMethodsToCurrentAllowlistedAdminsWithoutCreatingCases() throws Exception {
		mvc.perform(get("/api/admin/guardian-requests/prior-consent/options").header("Authorization", bearer(reviewer)))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.data.available").value(true)).andExpect(jsonPath("$.data.methods[0]").value("IN_PERSON"))
			.andExpect(jsonPath("$.data.maxAge").value("PT720H"))
			.andExpect(jsonPath("$.data.noticeVersion").value(policy.noticeVersion()))
			.andExpect(jsonPath("$.data.requiredScopes[0]").value("SERVICE"))
			.andExpect(jsonPath("$.data.optionalAiScope").value("EXTERNAL_AI"));
		User child = child();
		mvc.perform(get("/api/admin/guardian-requests/prior-consent/options").header("Authorization", bearer(child))).andExpect(status().isForbidden());
		assertThat(requests.count()).isZero(); assertThat(events.count()).isZero(); assertThat(operations.count()).isZero();
		verifyNoInteractions(ai, mail);
	}

	@Test void priorConsentCreatesOneCaseAndPreservesReceiptReviewAndAuditFacts() throws Exception {
		User child = child(); var body = prior("prior-create", null, Set.of("SERVICE"));
		mvc.perform(post(priorUrl(child)).header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(body))).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"))
			.andExpect(jsonPath("$.data.state").value("APPROVED")).andExpect(jsonPath("$.data.serviceApproved").value(true))
			.andExpect(jsonPath("$.data.externalAiApproved").value(false));
		var row = requests.findAll().getFirst(); var detail = service.detail(1L, row.id());
		assertThat(detail.confirmationMethod()).isEqualTo(GuardianTeamRequest.ConfirmationMethod.PRIOR_RECORD);
		assertThat(detail.priorConsentEvidence().method()).isEqualTo(GuardianPriorConsentPolicy.Method.IN_PERSON);
		assertThat(detail.priorConsentEvidence().receivedAt()).isEqualTo(baseline.minus(Duration.ofDays(1)));
		assertThat(detail.priorConsentEvidence().reviewedAt()).isEqualTo(baseline);
		assertThat(detail.priorConsentEvidence().consentValidUntil()).isEqualTo(baseline.plus(Duration.ofDays(2)));
		assertThat(detail.status().webDeclaredAt()).isNull(); assertThat(detail.guardianName()).isNull(); assertThat(detail.guardianContact()).isNull();
		assertThat(detail.status().approvedUntil()).isEqualTo(baseline.plus(Duration.ofDays(2)));
		assertThat(detail.events()).extracting(GuardianTeamDtos.Event::type)
			.containsExactlyInAnyOrder("REVIEWER_REQUESTED", "PRIOR_CONSENT_CONFIRMED", "PRIOR_CONSENT_APPROVED");
		assertThat(detail.events()).allSatisfy(event -> { assertThat(event.actorId()).isEqualTo(1L); assertThat(event.at()).isEqualTo(baseline); });
		assertThat(operations.count()).isEqualTo(1);
		mvc.perform(get("/api/users/me/guardian-requests").header("Authorization", bearer(child)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.priorConsentEvidence").doesNotExist())
			.andExpect(jsonPath("$.data.evidenceReference").doesNotExist());
		verifyNoInteractions(ai, mail);
	}

	@Test void priorConsentSupportsAnExistingFirstCaseWithoutFabricatingWebOrEmailEvidence() {
		User child = child(); var pending = intake(child, true).status();
		var approved = service.approvePriorConsent(1L, child.getId(), prior("prior-existing", pending, Set.of("SERVICE", "EXTERNAL_AI")));
		assertThat(approved.serviceApproved()).isTrue(); assertThat(approved.externalAiApproved()).isTrue();
		assertThat(approved.requestId()).isEqualTo(pending.requestId()); assertThat(requests.count()).isEqualTo(1);
		assertThat(approved.webDeclaredAt()).isNull(); assertThat(approved.explicitResponseAt()).isBefore(baseline);
		var detail = service.detail(1L, approved.requestId());
		assertThat(detail.events()).noneSatisfy(event -> assertThat(event.type()).isEqualTo("EXPLICIT_RESPONSE_CONFIRMED"));
		verifyNoInteractions(ai, mail);
	}

	@ParameterizedTest
	@ValueSource(strings = {"subjectMatched", "responseExplicitlyConsents", "legalGuardianDeclarationConfirmed", "guardianContactChecked",
		"noticeAndScopesMatched", "confirmationMethodChecked", "evidenceReferenceChecked", "validityChecked", "notWithdrawn"})
	void everyPriorConsentCheckIsRequiredAndFailureLeavesNoPartialCase(String check) throws Exception {
		User child = child(); var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("unchecked", null, Set.of("SERVICE")));
		node.put(check, false);
		mvc.perform(post(priorUrl(child)).header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(node))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		assertThat(requests.count()).isZero(); assertThat(events.count()).isZero(); assertThat(operations.count()).isZero();
		assertThat(users.findById(child.getId()).orElseThrow().getAgeVerificationState()).isNotEqualTo(AgeVerificationState.TEAM_APPROVED);
	}

	@Test void missingWrongOrUnapprovedEvidenceNeverCreatesACase() throws Exception {
		User child = child();
		for (String field : new String[]{"evidenceReference", "responseReceivedAt", "consentValidUntil", "relationship", "method"}) {
			var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("missing-" + field, null, Set.of("SERVICE")));
			node.putNull(field);
			mvc.perform(post(priorUrl(child)).header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(node))).andExpect(status().isBadRequest());
		}
		for (String[] invalid : new String[][]{{"evidenceReference", "https://example.invalid/child-document"}, {"method", "EMAIL_REPLY"},
			{"noticeVersion", "other-notice"}, {"noticeDigest", "b".repeat(64)}}) {
			var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("invalid-" + invalid[0], null, Set.of("SERVICE")));
			node.put(invalid[0], invalid[1]);
			mvc.perform(post(priorUrl(child)).header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(node))).andExpect(status().is(invalid[0].startsWith("notice") ? 409 : 400));
		}
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("no-service", null, Set.of("EXTERNAL_AI"))), ErrorCode.VALIDATION_FAILED);
		assertThat(requests.count()).isZero(); assertThat(events.count()).isZero(); assertThat(operations.count()).isZero();
		verifyNoInteractions(ai, mail);
	}

	@ParameterizedTest
	@EnumSource(value = GuardianPriorConsentPolicy.Method.class, names = "IN_PERSON", mode = EnumSource.Mode.EXCLUDE)
	void inPersonReviewNeverSubstitutesAnEmailPhoneOrWrittenMethod(GuardianPriorConsentPolicy.Method otherMethod) throws Exception {
		User child = child(); var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("wrong-source-" + otherMethod, null, Set.of("SERVICE")));
		node.put("method", otherMethod.name());
		mvc.perform(post(priorUrl(child)).header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(node))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		assertThat(requests.count()).isZero(); assertThat(events.count()).isZero(); assertThat(operations.count()).isZero();
		assertThat(users.findById(child.getId()).orElseThrow().getAgeVerificationState()).isNotEqualTo(AgeVerificationState.TEAM_APPROVED);
		verifyNoInteractions(ai, mail);
	}

	@Test void inPersonEvidenceCannotUseAnotherAccountsRequestOrAnUnmatchedSubject() throws Exception {
		User firstChild = child(), differentChild = child(); var first = intake(firstChild, false).status();
		assertError(() -> service.approvePriorConsent(1L, differentChild.getId(), prior("wrong-account", first, Set.of("SERVICE"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("unmatched-subject", first, Set.of("SERVICE")));
		node.put("subjectMatched", false);
		assertError(() -> service.approvePriorConsent(1L, firstChild.getId(), json.treeToValue(node, GuardianTeamDtos.PriorConsentApproval.class)), ErrorCode.VALIDATION_FAILED);
		assertThat(requests.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(1);
		assertThat(service.self(firstChild.getId()).status().serviceApproved()).isFalse();
		assertThat(users.findById(differentChild.getId()).orElseThrow().getAgeVerificationState()).isNotEqualTo(AgeVerificationState.TEAM_APPROVED);
		verifyNoInteractions(ai, mail);
	}

	@Test void priorConsentRejectsFutureExpiredAndOverAgeRecordsAndCapsApprovalAtTheEvidenceAgeLimit() throws Exception {
		User child = child();
		for (String[] invalid : new String[][]{{"responseReceivedAt", baseline.plusSeconds(1).toString()},
			{"responseReceivedAt", baseline.minus(Duration.ofDays(30)).toString()}, {"consentValidUntil", baseline.toString()}}) {
			var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("bad-time", null, Set.of("SERVICE")));
			node.put(invalid[0], invalid[1]);
			assertError(() -> service.approvePriorConsent(1L, child.getId(), json.treeToValue(node, GuardianTeamDtos.PriorConsentApproval.class)), ErrorCode.VALIDATION_FAILED);
		}
		assertThat(requests.count()).isZero();
		var node = (tools.jackson.databind.node.ObjectNode) json.valueToTree(prior("age-cap", null, Set.of("SERVICE")));
		node.put("responseReceivedAt", baseline.minus(Duration.ofDays(30)).plusSeconds(3600).toString());
		var approved = service.approvePriorConsent(1L, child.getId(), json.treeToValue(node, GuardianTeamDtos.PriorConsentApproval.class));
		assertThat(approved.approvedUntil()).isEqualTo(baseline.plusSeconds(3600));
		when(clock.instant()).thenReturn(approved.approvedUntil());
		assertThat(service.self(child.getId()).status().serviceApproved()).isFalse();
	}

	@Test void priorConsentRequiresCurrentDatabaseAdminAllowlistAndNeverAllowsSelfApproval() throws Exception {
		User child = child(); var body = prior("permission", null, Set.of("SERVICE")); String content = json.writeValueAsString(body);
		mvc.perform(post(priorUrl(child)).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isUnauthorized());
		mvc.perform(post(priorUrl(child)).header("Authorization", bearer(child)).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
		User unlisted = users.saveAndFlush(actor(UserRole.ADMIN, LocalDate.of(1980, 1, 1)));
		assertError(() -> service.approvePriorConsent(unlisted.getId(), child.getId(), body), ErrorCode.ACCESS_DENIED);
		assertError(() -> service.approvePriorConsent(1L, 1L, body), ErrorCode.ACCESS_DENIED);
		String staleToken = bearer(reviewer); reviewer.changeRole(UserRole.LEARNER); users.saveAndFlush(reviewer);
		mvc.perform(post(priorUrl(child)).header("Authorization", staleToken).contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isForbidden());
		assertError(() -> service.approvePriorConsent(1L, child.getId(), body), ErrorCode.ACCESS_DENIED);
		reviewer.changeRole(UserRole.ADMIN); reviewer.suspend("synthetic", 1L, baseline); users.saveAndFlush(reviewer);
		assertError(() -> service.approvePriorConsent(1L, child.getId(), body), ErrorCode.ACCESS_DENIED);
		assertThat(requests.count()).isZero(); assertThat(events.count()).isZero();
	}

	@Test void duplicateConcurrentPriorApprovalCommitsOneCaseOneDecisionAndOneConsentEpoch() throws Exception {
		User child = child(); var body = prior("concurrent-prior", null, Set.of("SERVICE"));
		long before = users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch(); CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(4)) {
			var calls = new ArrayList<Future<GuardianTeamDtos.Status>>();
			for (int i = 0; i < 4; i++) { calls.add(executor.submit(() -> { start.await(); return service.approvePriorConsent(1L, child.getId(), body); })); }
			start.countDown(); String id = calls.getFirst().get(15, TimeUnit.SECONDS).requestId();
			for (var call : calls) {
				var result = call.get(15, TimeUnit.SECONDS); assertThat(result.requestId()).isEqualTo(id);
				assertThat(result.state()).isEqualTo(GuardianTeamRequest.State.APPROVED); assertThat(result.serviceApproved()).isTrue();
			}
		} finally { start.countDown(); }
		assertThat(requests.count()).isEqualTo(1); assertThat(events.count()).isEqualTo(3); assertThat(operations.count()).isEqualTo(1);
		assertThat(users.findById(child.getId()).orElseThrow().getGuardianConsentEpoch()).isEqualTo(before + 1);
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("concurrent-prior", null, Set.of("SERVICE", "EXTERNAL_AI"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("different-key", null, Set.of("SERVICE"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertThat(events.count()).isEqualTo(3); verifyNoInteractions(ai, mail);
	}

	@Test void priorApprovalCannotOverwriteStaleRequestsOrReuseEvidenceAfterWithdrawal() {
		User child = child(); var pending = intake(child, false).status(); var stale = prior("stale", pending, Set.of("SERVICE"));
		var linked = link(child, pending).status();
		assertError(() -> service.approvePriorConsent(1L, child.getId(), stale), ErrorCode.GUARDIAN_STATE_CONFLICT);
		service.withdrawRequest(child.getId(), linked.requestId(), new GuardianTeamDtos.Mutation("withdraw-prior", linked.generation(), linked.revision()));
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("after-withdraw", linked, Set.of("SERVICE"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		var restarted = service.intake(child.getId(), new GuardianTeamDtos.Intake("restart-after-withdraw", null, null, false)).status();
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("old-evidence-next-generation", restarted, Set.of("SERVICE"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		assertThat(service.self(child.getId()).status().serviceApproved()).isFalse();
	}

	@Test void priorRecordCannotExpandAWebDeclarationOrApproveAnObsoletePolicySnapshot() {
		User child = child(); var pending = intake(child, false).status(); var linked = link(child, pending);
		var declared = service.consent(consent(linked.url().split("#token=", 2)[1], linked.status(), "declare-service", Set.of("SERVICE")), ip());
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("expand-prior", declared, Set.of("SERVICE", "EXTERNAL_AI"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		jdbc.update("update guardian_team_requests set configuration_digest=? where id=?", "b".repeat(64), declared.requestId());
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("stale-policy-prior", declared, Set.of("SERVICE"))), ErrorCode.GUARDIAN_TEAM_CONFIGURATION_CHANGED);
		assertThat(service.self(child.getId()).status().serviceApproved()).isFalse();
		assertThat(events.countByRequestIdAndEventTypeAndRecordedAtAfter(declared.requestId(), "PRIOR_CONSENT_APPROVED", baseline.minusSeconds(1))).isZero();
	}

	@Test void priorApprovalRetainsEmailVerificationAndSeparateExternalAiGatesAndRevocationErasesProvenance() throws Exception {
		User child = unverifiedChild(); var approved = service.approvePriorConsent(1L, child.getId(), prior("gates", null, Set.of("SERVICE")));
		mvc.perform(get("/api/materials").header("Authorization", bearer(child))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"));
		User saved = users.findById(child.getId()).orElseThrow(); saved.verifyEmail(baseline); users.saveAndFlush(saved);
		mvc.perform(get("/api/materials").header("Authorization", bearer(saved))).andExpect(status().isOk());
		assertThat(UserBusinessAccessState.from(saved).aiEligibilityFailure(clock, true, policy.configurationDigest())).isEqualTo(ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED);
		service.revoke(1L, approved.requestId(), new GuardianTeamDtos.Revoke("prior-revoke", approved.generation(), approved.revision(), GuardianTeamRequest.Reason.OPERATOR_REVOKED));
		assertError(() -> service.approvePriorConsent(1L, child.getId(), prior("gates", null, Set.of("SERVICE"))), ErrorCode.GUARDIAN_STATE_CONFLICT);
		var detail = service.detail(1L, approved.requestId()); assertThat(detail.priorConsentEvidence()).isNull(); assertThat(detail.evidenceReference()).isNull();
		assertThat(detail.status().serviceApproved()).isFalse();
		assertThat(UserBusinessAccessState.from(users.findById(child.getId()).orElseThrow()).eligibilityFailure(clock, true, policy.configurationDigest())).isNotNull();
		verifyNoInteractions(ai, mail);
	}

	@Test void ordinaryConfirmationStillRejectsPriorTimesAndDoesNotAcceptThePriorRecordMarker() {
		User child = child(); var pending = intake(child, false).status();
		for (var method : new GuardianTeamRequest.ConfirmationMethod[]{GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY, GuardianTeamRequest.ConfirmationMethod.PRIOR_RECORD}) {
			assertError(() -> service.confirm(1L, pending.requestId(), new GuardianTeamDtos.Confirmation("ordinary-" + method,
				pending.generation(), pending.revision(), method, "synthetic-prior-record", method == GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY ? baseline.minusSeconds(60) : baseline, policy.noticeVersion(), policy.noticeDigest(),
				GuardianTeamRequest.Relationship.PARENT, Set.of("SERVICE"), true, true, true, true, true)), ErrorCode.VALIDATION_FAILED);
		}
		assertThat(approve(confirm(pending, Set.of("SERVICE")), "ordinary-still-works").serviceApproved()).isTrue();
		assertThat(service.detail(1L, pending.requestId()).priorConsentEvidence()).isNull();
	}

	private GuardianTeamDtos.PriorConsentApproval prior(String key, GuardianTeamDtos.Status status, Set<String> scopes) {
		return new GuardianTeamDtos.PriorConsentApproval(key, status == null ? null : status.requestId(), status == null ? 0 : status.generation(),
			status == null ? 0 : status.revision(), GuardianPriorConsentPolicy.Method.IN_PERSON, "synthetic-retained-record",
			baseline.minus(Duration.ofDays(1)), baseline.plus(Duration.ofDays(2)), policy.noticeVersion(), policy.noticeDigest(),
			GuardianTeamRequest.Relationship.PARENT, scopes, true, true, true, true, true, true, true, true, true);
	}
	private String priorUrl(User child) { return "/api/admin/guardian-requests/users/" + child.getId() + "/prior-consent/approval"; }
	private User child() { return users.saveAndFlush(actor(UserRole.LEARNER, LocalDate.of(2016, 1, 1))); }
	private User unverifiedChild() {
		User user = User.create("synthetic-" + UUID.randomUUID() + "@example.invalid", passwords.encode("synthetic-password"), "합성 계정", UserRole.LEARNER);
		user.recordSignupDateOfBirth(LocalDate.of(2016, 1, 1)); user.beginEmailVerification(); return users.saveAndFlush(user);
	}
	private User actor(UserRole role, LocalDate date) {
		User user = User.create("synthetic-" + UUID.randomUUID() + "@example.invalid", passwords.encode("synthetic-password"), "합성 계정", role);
		user.recordSignupDateOfBirth(date); user.verifyEmail(baseline); return user;
	}
	private GuardianTeamDtos.View intake(User child, boolean contact) {
		return service.intake(child.getId(), new GuardianTeamDtos.Intake("intake", contact ? "합성 보호자" : null,
			contact ? "guardian@example.invalid" : null, contact));
	}
	private GuardianTeamDtos.Link link(User child, GuardianTeamDtos.Status status) {
		return service.issueLink(child.getId(), status.requestId(), new GuardianTeamDtos.Mutation("link", status.generation(), status.revision()));
	}
	private GuardianTeamDtos.Consent consent(String token, GuardianTeamDtos.Status status, String key, Set<String> scopes) {
		return new GuardianTeamDtos.Consent(token, key, status.generation(), status.revision(), status.noticeVersion(), status.noticeDigest(),
			true, true, GuardianTeamRequest.Relationship.PARENT, scopes, "합성 보호자", "guardian@example.invalid");
	}
	private GuardianTeamDtos.Status confirm(GuardianTeamDtos.Status status, Set<String> scopes) {
		return service.confirm(1L, status.requestId(), new GuardianTeamDtos.Confirmation("confirmation-" + status.generation(), status.generation(), status.revision(),
			GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY, "opaque-ref", baseline, status.noticeVersion(), status.noticeDigest(),
			GuardianTeamRequest.Relationship.PARENT, scopes, true, true, true, true, true));
	}
	private GuardianTeamDtos.Status approve(GuardianTeamDtos.Status status, String key) {
		return service.decide(1L, status.requestId(), new GuardianTeamDtos.Decision(key, status.generation(), status.revision(),
			GuardianTeamDtos.DecisionKind.APPROVE, null, true, true, true, true));
	}
	private static boolean tryDecision(Runnable operation) {
		try { operation.run(); return true; }
		catch (BusinessException conflict) { assertThat(conflict.errorCode()).isEqualTo(ErrorCode.GUARDIAN_STATE_CONFLICT); return false; }
	}
	private String bearer(User user) { return "Bearer " + tokens.createAccessToken(user); }
	private String ip() { return "synthetic-" + UUID.randomUUID(); }
	private static void assertError(Runnable operation, ErrorCode code) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(code));
	}
}
