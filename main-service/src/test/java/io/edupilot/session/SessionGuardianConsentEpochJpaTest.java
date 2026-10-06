package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiClientProperties;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.ai.dto.QuizQuestionPreview;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.AgeVerificationState;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import tools.jackson.databind.ObjectMapper;

/** 합성 승인 기록, 가짜 AI와 메모리 SSE 수신기로 검증한다. 실제 업체나 아동 정보는 사용하지 않는다. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-consent-epoch;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-consent-epoch",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging",
	// 시험용 설정이며 실제 서비스 동의문, 검토 권한이나 보존 기간을 결정하지 않는다.
	"edupilot.guardian.team.enabled=true", "edupilot.guardian.team.policy-confirmed=true",
	"edupilot.guardian.team.portal-base-url=https://synthetic.example.test",
	"edupilot.guardian.team.notice-url=https://synthetic.example.test/notice",
	"edupilot.guardian.team.notice-version=synthetic-1",
	"edupilot.guardian.team.notice-digest=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
	"edupilot.guardian.team.collection-items-text=합성 시험 정보",
	"edupilot.guardian.team.purposes-text=합성 시험 확인",
	"edupilot.guardian.team.retention-text=합성 시험용 보관 안내",
	"edupilot.guardian.team.refusal-text=합성 시험용 거부 안내",
	"edupilot.guardian.team.reply-contact=reviewer@synthetic.example.test",
	"edupilot.guardian.team.reply-channel=EMAIL_REPLY", "edupilot.guardian.team.reviewer-ids=1",
	"edupilot.guardian.team.required-scopes=SERVICE", "edupilot.guardian.team.optional-ai-scope=EXTERNAL_AI",
	"edupilot.guardian.team.optional-consent-text=합성 시험 외부 AI 선택 동의",
	"edupilot.guardian.team.link-ttl=PT1H", "edupilot.guardian.team.request-ttl=PT96H",
	"edupilot.guardian.team.approved-evidence-retention=P30D", "edupilot.guardian.team.approval-validity=P30D"
})
@ActiveProfiles("jpa-context")
class SessionGuardianConsentEpochJpaTest {
	private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private ChatMessageRepository messages;
	@Autowired private MaterialAccessService materialAccess;
	@Autowired private SessionStreamAccessGuard access;
	@Autowired private TurnPersistenceService persistence;
	@Autowired private TurnClaimService claims;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private GuardianTeamProperties policy;
	@MockitoBean private AiClient ai;
	@MockitoBean private AiClientProperties aiProperties;
	@MockitoBean private AiQuotaService quota;
	@MockitoBean private AiUsageService usage;
	@MockitoBean private ConversationSummaryDispatcher summaries;
	@MockitoBean private SessionPageRecordRepository pageRecords;
	@MockitoBean private LearnerMemoryPromotionService memory;
	@MockitoBean private Clock clock;
	private final TurnPreparationService preparation = mock(TurnPreparationService.class);
	private final TurnSnapshotService snapshots = mock(TurnSnapshotService.class);
	private final TurnResponseValidator validator = mock(TurnResponseValidator.class);
	private final RecordingEmitter emitter = new RecordingEmitter();
	private User learner;
	private LearningSession session;
	private SessionStreamService streams;

	@BeforeEach
	void createSyntheticApprovedAccount() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(aiProperties.turnReadTimeout()).thenReturn(Duration.ofSeconds(30));
		learner = User.create("synthetic-" + UUID.randomUUID() + "@example.test", "!synthetic", "합성 학습자");
		learner.recordSignupDateOfBirth(LocalDate.of(2014, 1, 1));
		learner.verifyEmail(NOW.minusSeconds(60));
		learner.recordGuardianTeamApproval(NOW.plusSeconds(3600), true);
		assertThat(policy.ready()).isTrue();
		learner.recordGuardianTeamPolicyDigest(policy.configurationDigest());
		learner = users.saveAndFlush(learner);
		LearningMaterial material = LearningMaterial.create(learner, "합성 자료", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(1);
		material = materials.saveAndFlush(material);
		session = sessions.saveAndFlush(LearningSession.create(learner, material));
		streams = new SessionStreamService(sessions, materialAccess, access, () -> emitter);
		when(preparation.prepare(learner.getId(), session.getId(), "synthetic-request", "현재 페이지 설명 요청: NORMAL", null))
			.thenReturn(new PreparedTurn(501L));
		when(snapshots.build(learner.getId(), session.getId(), 501L, true)).thenReturn(new TurnSnapshot(
			Map.of("sessionId", session.getId(), "currentPage", 1), Map.of("currentPageText", "합성 자료 내용"), material.getId(), false));
	}

	@AfterEach
	void shutdownStream() { streams.shutdown(); }

	@ParameterizedTest
	@EnumSource(Packet.class)
	void revokeThenReapproveStillSuppressesEveryPacketFromTheOlderSseConnection(Packet packet) {
		streams.connect(learner.getId(), session.getId());
		AiStreamCancellation upstream = new AiStreamCancellation();
		SessionStreamConnection connection = streams.beginTurn(learner.getId(), session.getId(), "synthetic-request", upstream).orElseThrow();
		connection.send(TurnStreamEvent.contentDelta("이전 동의의 합성 응답"));
		int sent = emitter.deliveries.get();

		revokeAndReapprove();

		try { send(packet, connection); } catch (AiClientException interrupted) {
			assertThat(interrupted.retryable()).isFalse();
			assertThat(interrupted.getCause()).isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		}
		assertThat(emitter.deliveries.get()).isEqualTo(sent);
		assertThat(connection.isClosed()).isTrue();
		assertThat(connection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.ACCESS_REVOKED);
		assertThat(upstream.isCancelled()).isTrue();
		assertThat(upstream.isUserCancelled()).isFalse();
		verifyNoInteractions(ai);
	}

	@Test
	void oldIdleSseConnectionCannotStartAnExternalAiRequestAfterReapproval() {
		streams.connect(learner.getId(), session.getId());
		revokeAndReapprove();

		assertThatThrownBy(() -> turnService(persistence).execute(learner.getId(), session.getId(), request()))
			.isInstanceOf(AiClientException.class);

		verifyNoInteractions(ai);
		assertThat(emitter.deliveries.get()).isEqualTo(1);
		assertNoAiMessage();
	}

	@Test
	void lateJsonAiResultIsDiscardedAfterRevokeAndReapprovalDuringTheCall() {
		when(ai.executeTurn(any(), any(Duration.class))).thenAnswer(invocation -> {
			revokeAndReapprove();
			return response(invocation.getArgument(0, io.edupilot.ai.dto.TurnRequest.class).turnId());
		});

		assertChanged(() -> turnService(persistence).execute(learner.getId(), session.getId(), request()));

		assertNoAiMessage();
		verifyNoInteractions(validator);
	}

	@Test
	void lateStreamingAiResultWithoutAnotherDeltaIsDiscardedAfterReapproval() {
		streams.connect(learner.getId(), session.getId());
		when(ai.executeTurnStream(any(), any(), any(), any())).thenAnswer(invocation -> {
			Consumer<TurnStreamEvent> listener = invocation.getArgument(1);
			listener.accept(TurnStreamEvent.contentDelta("승인된 합성 부분 응답"));
			revokeAndReapprove();
			return response(invocation.getArgument(0, io.edupilot.ai.dto.TurnRequest.class).turnId());
		});

		assertChanged(() -> turnService(persistence).execute(learner.getId(), session.getId(), request()));

		assertNoAiMessage();
		assertThat(emitter.deliveries.get()).isEqualTo(2); // ready와 변경 전에 허용된 부분 응답
		verifyNoInteractions(validator);
	}

	@ParameterizedTest
	@EnumSource(ResultType.class)
	void currentReadAccountLockRejectsStaleCompletedAndCancelledWrites(ResultType resultType) throws Exception {
		long oldEpoch = access.captureAccess(learner.getId()).guardianConsentEpoch();
		claims.claim(learner.getId(), session.getId(), "synthetic-request");
		try (var executor = Executors.newSingleThreadExecutor()) {
			new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
				assertThat(users.findById(learner.getId()).orElseThrow().getGuardianConsentEpoch()).isEqualTo(oldEpoch);
				try { executor.submit(this::revokeAndReapprove).get(10, TimeUnit.SECONDS); }
				catch (Exception failure) { throw new IllegalStateException("합성 동의 변경 실패", failure); }
				assertChanged(() -> persist(resultType, oldEpoch));
				transaction.setRollbackOnly();
			});
		} finally { claims.release(session.getId(), "synthetic-request"); }
		assertNoAiMessage();
		verifyNoInteractions(summaries, pageRecords);
	}

	@Test
	void finalJsonReturnIsFencedEvenWhenRevocationOccursAfterPersistence() {
		TurnPersistenceService stub = mock(TurnPersistenceService.class);
		when(ai.executeTurn(any(), any(Duration.class))).thenAnswer(invocation ->
			response(invocation.getArgument(0, io.edupilot.ai.dto.TurnRequest.class).turnId()));
		when(stub.persist(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any(), any(),
			org.mockito.ArgumentMatchers.anyBoolean(), any())).thenAnswer(invocation -> {
			long capturedEpoch = invocation.getArgument(2);
			assertThat(capturedEpoch).isEqualTo(learner.getGuardianConsentEpoch());
			revokeAndReapprove();
			return new PersistedTurn("synthetic-turn", session.getId(), List.of(), List.of(),
				new io.edupilot.session.dto.TurnStateResponse(1, PageStatus.EXPLAINED, null), null, session.getMaterialId());
		});

		assertChanged(() -> turnService(stub).execute(learner.getId(), session.getId(), request()));

		verifyNoInteractions(memory);
	}

	@Test
	void newConnectionAndNewGenerationCanPersistAfterReapproval() {
		streams.connect(learner.getId(), session.getId());
		SessionStreamAccessGuard.Access oldAccess = access.captureAccess(learner.getId());
		revokeAndReapprove();
		assertChanged(() -> access.assertAccessible(learner.getId(), session.getId(), oldAccess));
		streams.connect(learner.getId(), session.getId());
		SessionStreamAccessGuard.Access current = access.captureAccess(learner.getId());
		assertThat(current.guardianConsentEpoch()).isEqualTo(oldAccess.guardianConsentEpoch() + 2);
		claims.claim(learner.getId(), session.getId(), "synthetic-request");
		try {
			PersistedTurn stored = persist(ResultType.CANCELLED, current.guardianConsentEpoch());
			assertThat(stored.messages()).singleElement().satisfies(message -> assertThat(message.content()).isEqualTo("합성 부분 응답"));
		} finally { claims.release(session.getId(), "synthetic-request"); }
	}

	@ParameterizedTest
	@EnumSource(ResultType.class)
	void currentAccountLockRejectsApprovalFromAnotherPolicyEvenWhenEpochMatches(ResultType resultType) {
		long epoch = access.captureAccess(learner.getId()).guardianConsentEpoch();
		claims.claim(learner.getId(), session.getId(), "synthetic-request");
		try {
			recordEarlierApprovalPolicy();
			assertThat(users.findById(learner.getId()).orElseThrow().getGuardianConsentEpoch()).isEqualTo(epoch);
			assertThatThrownBy(() -> persist(resultType, epoch)).isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED));
		} finally { claims.release(session.getId(), "synthetic-request"); }
		assertNoAiMessage();
		verifyNoInteractions(summaries, pageRecords);
	}

	@Test
	void olderPolicyApprovalCannotDeliverAnSseCompletionAtTheSameEpoch() {
		streams.connect(learner.getId(), session.getId());
		AiStreamCancellation upstream = new AiStreamCancellation();
		SessionStreamConnection connection = streams.beginTurn(learner.getId(), session.getId(), "synthetic-request", upstream).orElseThrow();
		long epoch = access.captureAccess(learner.getId()).guardianConsentEpoch();

		recordEarlierApprovalPolicy();

		assertThat(users.findById(learner.getId()).orElseThrow().getGuardianConsentEpoch()).isEqualTo(epoch);
		assertThatThrownBy(() -> connection.sendCompleted("synthetic-request", mock(TurnResponse.class)))
			.isInstanceOfSatisfying(AiClientException.class, interrupted ->
				assertThat(interrupted.getCause()).isInstanceOfSatisfying(BusinessException.class,
					failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED)));
		assertThat(emitter.deliveries.get()).isEqualTo(1);
		assertThat(upstream.isCancelled()).isTrue();
		assertThat(connection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.ACCESS_REVOKED);
	}

	@Test
	void jsonResultIsDiscardedWhenTheApprovalPolicyNoLongerMatchesAfterTheCall() {
		when(ai.executeTurn(any(), any(Duration.class))).thenAnswer(invocation -> {
			recordEarlierApprovalPolicy();
			return response(invocation.getArgument(0, io.edupilot.ai.dto.TurnRequest.class).turnId());
		});

		assertThatThrownBy(() -> turnService(persistence).execute(learner.getId(), session.getId(), request()))
			.isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED));

		assertNoAiMessage();
		verifyNoInteractions(validator);
	}

	private SessionTurnService turnService(TurnPersistenceService target) {
		return new SessionTurnService(claims, preparation, snapshots, ai, usage, quota, validator, target,
			memory, streams, aiProperties, users, materialAccess, access);
	}

	private void revokeAndReapprove() {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			User current = users.findByIdForUpdate(learner.getId()).orElseThrow();
			current.clearGuardianTeamApproval(false);
			current.recordGuardianTeamApproval(NOW.plusSeconds(3600), true);
			current.recordGuardianTeamPolicyDigest(policy.configurationDigest());
			users.flush();
		});
		assertThat(users.findById(learner.getId()).orElseThrow().getAgeVerificationState()).isEqualTo(AgeVerificationState.TEAM_APPROVED);
	}

	private void recordEarlierApprovalPolicy() {
		String earlierPolicy = "b".repeat(64);
		assertThat(earlierPolicy).isNotEqualTo(policy.configurationDigest());
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			users.findByIdForUpdate(learner.getId()).orElseThrow().recordGuardianTeamPolicyDigest(earlierPolicy);
			users.flush();
		});
	}

	private PersistedTurn persist(ResultType type, long epoch) {
		return type == ResultType.CANCELLED
			? persistence.persistCancelled(learner.getId(), UserRole.LEARNER, epoch, session.getId(), "synthetic-request", "synthetic-turn", "합성 부분 응답")
			: persistence.persist(learner.getId(), UserRole.LEARNER, epoch, session.getId(), "synthetic-request",
				TurnEventType.EXPLAIN_CURRENT_PAGE, null, 501L, false, response("synthetic-turn"));
	}

	private TurnRequest request() {
		return new TurnRequest("synthetic-request", "EXPLAIN_CURRENT_PAGE", new ObjectMapper().createObjectNode().put("detailLevel", "NORMAL"));
	}

	private io.edupilot.ai.dto.TurnResponse response(String turnId) {
		return new io.edupilot.ai.dto.TurnResponse("1.0", turnId, "EXPLAIN", List.of(),
			List.of(Map.of("messageType", "EXPLANATION", "content", "합성 AI 응답")),
			Map.of("pageStatus", "EXPLAINED"), List.of(), null, List.of(), null, null);
	}

	private void assertNoAiMessage() {
		assertThat(messages.findBySession_IdOrderByCreatedAtDescIdDesc(session.getId(), org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();
	}

	private void assertChanged(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
			failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
	}

	private void send(Packet packet, SessionStreamConnection connection) {
		switch (packet) {
			case READY -> connection.sendReady(NOW);
			case STATUS -> connection.send(TurnStreamEvent.status("합성 단계"));
			case THOUGHT -> connection.send(TurnStreamEvent.thoughtSummary("합성 생각"));
			case CONTENT -> connection.send(TurnStreamEvent.contentDelta("합성 답변"));
			case QUIZ -> connection.send(TurnStreamEvent.quizQuestion(mock(QuizQuestionPreview.class)));
			case UI_ACTION -> connection.sendUiAction(UiAction.noteProposal("합성 노트"));
			case COMPLETED -> connection.sendCompleted("synthetic-request", mock(TurnResponse.class));
			case ERROR -> connection.sendError(new SessionStreamError("SYNTHETIC", "INTERNAL", "합성 오류", false, "synthetic-trace"));
			case HEARTBEAT -> connection.sendHeartbeatIfIdle(0);
		}
	}

	private enum ResultType { COMPLETED, CANCELLED }
	private enum Packet { READY, STATUS, THOUGHT, CONTENT, QUIZ, UI_ACTION, COMPLETED, ERROR, HEARTBEAT }
	private static final class RecordingEmitter extends SseEmitter {
		private final AtomicInteger deliveries = new AtomicInteger();
		@Override public void send(SseEventBuilder event) throws IOException { deliveries.incrementAndGet(); }
	}
}
