package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.ai.AiClient;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.assessment.AssessmentPersistenceService;
import io.edupilot.assessment.LearningSupportPipeline;
import io.edupilot.diagnosis.DiagnosisPersistenceService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.material.DocChatService;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialDocChatContextService;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.memory.LearnerMemoryRepository;
import io.edupilot.material.dto.DocChatRequest;
import io.edupilot.quiz.QuizDocChatContextService;
import io.edupilot.quiz.QuizPostGradingContext;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

class GuardianConsentFenceTest {
	private static final Instant NOW = Instant.parse("2026-10-06T01:00:00Z");
	private static final String DIGEST = "a".repeat(64);
	private final Map<Long, User> accounts = new HashMap<>();
	private UserRepository users;
	private GuardianTeamProperties policy;
	private EntityManager entities;
	private GuardianConsentFence fence;
	private User child;

	@BeforeEach
	void setUp() {
		accounts.clear();
		users = mock(UserRepository.class);
		when(users.findBusinessAccessStateById(anyLong())).thenAnswer(call ->
			Optional.ofNullable(accounts.get(call.getArgument(0))).map(UserBusinessAccessState::from));
		when(users.findByIdForBusinessAccess(anyLong())).thenAnswer(call ->
			Optional.ofNullable(accounts.get(call.getArgument(0))));
		policy = mock(GuardianTeamProperties.class);
		when(policy.ready()).thenReturn(true);
		when(policy.configurationDigest()).thenReturn(DIGEST);
		entities = io.edupilot.GuardianConsentFenceTestSupport.lockedUserEntities();
		fence = new GuardianConsentFence(users, mock(LearningMaterialRepository.class),
			mock(LearningSessionRepository.class), Clock.fixed(NOW, ZoneOffset.UTC), policy);
		ReflectionTestUtils.setField(fence, "entityManager", entities);
		child = approvedChild(1L, true);
	}

	@Test
	void serviceConsentWithoutExternalAiConsentCannotSendAnyData() {
		child = approvedChild(1L, false);
		AiClient ai = mock(AiClient.class);
		DocChatService service = docChat(ai);
		assertFailure(() -> service.askMaterial(1L, 10L, new DocChatRequest("합성 질문", List.of())),
			ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED);
		verifyNoInteractions(ai);
	}

	@Test
	void optionalAiLearningSupportIsSkippedAfterDeterministicGradingWithoutSendingData() {
		child = approvedChild(1L, false);
		AiClient ai = mock(AiClient.class);
		AssessmentPersistenceService assessments = mock(AssessmentPersistenceService.class);
		DiagnosisPersistenceService diagnoses = mock(DiagnosisPersistenceService.class);
		LearnerMemoryRepository memories = mock(LearnerMemoryRepository.class);
		LearningSupportPipeline pipeline = new LearningSupportPipeline(ai, mock(AiUsageService.class),
			mock(AiQuotaService.class), users, assessments, diagnoses, memories, mock(MaterialAccessService.class), fence);
		QuizPostGradingContext context = mock(QuizPostGradingContext.class);
		when(context.userId()).thenReturn(1L);
		when(context.defaultUiActions()).thenReturn(List.of());
		var result = pipeline.onGraded(context);
		assertThat(result.uiActions()).isEmpty();
		assertThat(result.guardianConsent()).isNull();
		verifyNoInteractions(ai, assessments, diagnoses, memories);
	}

	@Test
	void revokeAndReapproveWhileAiRunsCannotReturnTheOldResponse() {
		AiClient ai = mock(AiClient.class);
		when(ai.docChat(any())).thenAnswer(call -> {
			child.clearGuardianTeamApproval(false);
			child.recordGuardianTeamApproval(NOW.plusSeconds(600), true);
			child.recordGuardianTeamPolicyDigest(DIGEST);
			return new io.edupilot.ai.dto.DocChatResponse("1.0", "늦은 합성 응답", List.of(), null);
		});
		assertFailure(() -> docChat(ai).askMaterial(1L, 10L, new DocChatRequest("합성 질문", List.of())),
			ErrorCode.GUARDIAN_CONSENT_CHANGED);
	}

	@Test
	void postGradingLearningSupportKeepsTheSubmittingWorkflowEpoch() {
		GuardianConsentFence.Snapshot original = fence.capture(1L);
		child.clearGuardianTeamApproval(false);
		child.recordGuardianTeamApproval(NOW.plusSeconds(600), true);
		child.recordGuardianTeamPolicyDigest(DIGEST);
		AiClient ai = mock(AiClient.class);
		AssessmentPersistenceService assessments = mock(AssessmentPersistenceService.class);
		DiagnosisPersistenceService diagnoses = mock(DiagnosisPersistenceService.class);
		LearnerMemoryRepository memories = mock(LearnerMemoryRepository.class);
		LearningSupportPipeline pipeline = new LearningSupportPipeline(ai, mock(AiUsageService.class),
			mock(AiQuotaService.class), users, assessments, diagnoses, memories, mock(MaterialAccessService.class), fence);
		QuizPostGradingContext context = mock(QuizPostGradingContext.class);
		when(context.userId()).thenReturn(1L);
		when(context.guardianConsent()).thenReturn(original);
		assertFailure(() -> pipeline.onGraded(context), ErrorCode.GUARDIAN_CONSENT_CHANGED);
		verifyNoInteractions(ai, assessments, diagnoses, memories);
	}

	@Test
	void revokeAndReapproveCannotInvokeAnOldResultSave() {
		GuardianConsentFence.Snapshot consent = fence.capture(1L);
		child.clearGuardianTeamApproval(false);
		child.recordGuardianTeamApproval(NOW.plusSeconds(600), true);
		child.recordGuardianTeamPolicyDigest(DIGEST);
		AtomicBoolean saved = new AtomicBoolean();
		assertFailure(() -> fence.complete(consent, () -> saved.compareAndSet(false, true)),
			ErrorCode.GUARDIAN_CONSENT_CHANGED);
		assertThat(saved).isFalse();
	}

	@Test
	void expiredApprovalCannotInvokeResultSaveEvenWhenEpochMatches() {
		GuardianConsentFence.Snapshot consent = fence.capture(1L);
		ReflectionTestUtils.setField(child, "guardianApprovedUntil", NOW);
		AtomicBoolean saved = new AtomicBoolean();
		assertFailure(() -> fence.complete(consent, () -> saved.compareAndSet(false, true)),
			ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(saved).isFalse();
	}

	@Test
	void disabledTeamReviewBlocksPreviouslyApprovedChild() {
		when(policy.ready()).thenReturn(false);
		assertFailure(() -> fence.capture(1L), ErrorCode.AGE_VERIFICATION_REQUIRED);
	}

	@Test
	void changedReviewedNoticeCannotReuseApproval() {
		GuardianConsentFence.Snapshot consent = fence.capture(1L);
		when(policy.configurationDigest()).thenReturn("b".repeat(64));
		AtomicBoolean saved = new AtomicBoolean();
		assertFailure(() -> fence.complete(consent, () -> saved.compareAndSet(false, true)),
			ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(saved).isFalse();
	}

	@Test
	void currentLockedDatabaseRowOverridesAStaleManagedAccount() {
		GuardianConsentFence.Snapshot consent = fence.capture(1L);
		doAnswer(call -> { child.clearGuardianTeamApproval(false); return null; })
			.when(entities).refresh(child, LockModeType.PESSIMISTIC_READ);
		AtomicBoolean saved = new AtomicBoolean();
		assertFailure(() -> fence.complete(consent, () -> saved.compareAndSet(false, true)),
			ErrorCode.GUARDIAN_CONSENT_CHANGED);
		assertThat(saved).isFalse();
	}

	@Test
	void reportSubjectsAreLockedInUserOrderAndDuplicateSubjectsLockOnce() {
		approvedChild(2L, true);
		GuardianConsentFence.Snapshot one = fence.capture(1L);
		GuardianConsentFence.Snapshot two = fence.capture(2L);
		assertThat(fence.complete(List.of(two, one, two), () -> "저장됨")).isEqualTo("저장됨");
		var order = inOrder(users);
		order.verify(users).findByIdForBusinessAccess(1L);
		order.verify(users).findByIdForBusinessAccess(2L);
		order.verifyNoMoreInteractions();
	}

	@Test
	void inconsistentSnapshotsForSameAccountCannotSave() {
		AtomicBoolean saved = new AtomicBoolean();
		assertFailure(() -> fence.complete(List.of(new GuardianConsentFence.Snapshot(1L, 1),
			new GuardianConsentFence.Snapshot(1L, 3)), () -> saved.compareAndSet(false, true)),
			ErrorCode.GUARDIAN_CONSENT_CHANGED);
		assertThat(saved).isFalse();
		verifyNoInteractions(entities);
	}

	@Test
	void withdrawnAccountCannotCompleteAnEarlierResult() {
		GuardianConsentFence.Snapshot consent = fence.capture(1L);
		child.withdraw();
		assertFailure(() -> fence.complete(consent, () -> "금지됨"), ErrorCode.GUARDIAN_CONSENT_CHANGED);
	}

	private User approvedChild(Long id, boolean externalAi) {
		User user = User.create("child" + id + "@example.invalid", "synthetic-hash", "합성 아동", UserRole.LEARNER);
		user.recordSignupDateOfBirth(LocalDate.of(2013, 1, 1));
		user.verifyEmail(NOW.minusSeconds(60));
		ReflectionTestUtils.setField(user, "id", id);
		user.recordGuardianTeamApproval(NOW.plusSeconds(600), externalAi);
		user.recordGuardianTeamPolicyDigest(DIGEST);
		accounts.put(id, user);
		return user;
	}

	private DocChatService docChat(AiClient ai) {
		when(users.findById(1L)).thenReturn(Optional.of(child));
		MaterialDocChatContextService contexts = mock(MaterialDocChatContextService.class);
		when(contexts.build(1L, 10L)).thenReturn(List.of());
		return new DocChatService(ai, mock(AiUsageService.class), mock(AiQuotaService.class), users,
			contexts, mock(QuizDocChatContextService.class), fence);
	}

	private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode expected) {
		assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(expected));
	}
}
