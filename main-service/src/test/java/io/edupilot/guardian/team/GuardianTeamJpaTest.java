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
		var declaration = service.consent(consent(raw, issued.status(), "structured-declare", Set.of("SERVICE", "EXTERNAL_AI")), ip());
		mvc.perform(get("/api/admin/guardian-requests/" + declaration.requestId()).header("Authorization", bearer(reviewer)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.replyChannel").value("EMAIL_REPLY"))
			.andExpect(jsonPath("$.data.generationStartedAt").value(baseline.toString()))
			.andExpect(jsonPath("$.data.declaredScopes", org.hamcrest.Matchers.containsInAnyOrder("SERVICE", "EXTERNAL_AI")))
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

	@Test void aChangedPolicyDoesNotAdvertiseItsReplyMethodAsBelongingToAnOldCase() {
		User child = child(); var first = intake(child, false);
		jdbc.update("update guardian_team_requests set configuration_digest=? where id=?", "b".repeat(64), first.status().requestId());
		var view = service.self(child.getId()); var detail = service.detail(1L, first.status().requestId());
		assertThat(view.status().currentNotice()).isFalse(); assertThat(view.replyChannel()).isNull(); assertThat(view.forms()).isEmpty();
		assertThat(detail.replyChannel()).isNull(); assertThat(detail.forms()).isEmpty();
		assertThat(service.entry(child.getId()).canStartRequest()).isFalse(); verifyNoInteractions(ai, mail);
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
