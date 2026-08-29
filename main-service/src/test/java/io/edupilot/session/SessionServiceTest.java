package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.diagnosis.DiagnosisService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.session.dto.PendingDiagnosisResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private MaterialAccessService materialAccessService;

	@Mock
	private UserRepository userRepository;

	@Mock
	private DiagnosisService diagnosisService;

	private SessionService sessionService;
	private User owner;
	private LearningMaterial material;

	@BeforeEach
	void setUp() {
		sessionService = new SessionService(
			sessionRepository,
			userRepository,
			new StateReducer(),
			Clock.fixed(NOW, ZoneOffset.UTC),
			diagnosisService,
			materialAccessService,
			new UiActionResolver()
		);
		owner = User.create("owner@example.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		material = LearningMaterial.create(owner, "자료", "materials/test.pdf");
		ReflectionTestUtils.setField(material, "id", 10L);
	}

	@Test
	void createsReadySessionAndReusesExistingActiveSession() {
		material.markReady(3);
		when(materialAccessService.requireAccessibleForUpdate(1L, 10L))
			.thenReturn(material);
		when(userRepository.getReferenceById(1L)).thenReturn(owner);
		when(sessionRepository.findByUser_IdAndMaterial_IdAndStatus(
			1L,
			10L,
			SessionStatus.ACTIVE
		)).thenReturn(Optional.empty());
		when(sessionRepository.saveAndFlush(any(LearningSession.class)))
			.thenAnswer(invocation -> persisted(invocation.getArgument(0), 100L));

		var created = sessionService.create(1L, 10L);

		assertThat(created.reused()).isFalse();
		assertThat(created.currentPage()).isEqualTo(1);
		assertThat(created.pageStatus()).isEqualTo(PageStatus.NOT_EXPLAINED);
		assertThat(created.uiActions().getFirst().content())
			.isEqualTo("강의를 시작할까요?");

		LearningSession existing = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findByUser_IdAndMaterial_IdAndStatus(
			1L,
			10L,
			SessionStatus.ACTIVE
		)).thenReturn(Optional.of(existing));

		var reused = sessionService.create(1L, 10L);

		assertThat(reused.reused()).isTrue();
		assertThat(reused.sessionId()).isEqualTo(100L);
	}

	@Test
	void detailRestoresPendingDiagnosisFromStoredReference() {
		material.markReady(3);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		session.startDiagnosis(
			30L,
			UiAction.diagnosisQuestion("진단 질문", 30L)
		);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(diagnosisService.findPending(100L, 30L))
			.thenReturn(Optional.of(
				new PendingDiagnosisResponse(30L, "진단 질문")
			));

		var response = sessionService.detail(1L, 100L);

		assertThat(response.pendingDiagnosis().diagnosisId()).isEqualTo(30L);
		assertThat(response.pendingDiagnosis().prompt())
			.isEqualTo("진단 질문");
		assertThat(response.uiActions())
			.containsExactly(UiAction.diagnosisQuestion("진단 질문", 30L));
		assertThat(response.uiActions().getFirst().yesEvent()).isNull();
		assertThat(response.uiActions().getFirst().noEvent()).isNull();
		assertThat(response.uiActions().getFirst().diagnosisId())
			.isEqualTo(response.pendingDiagnosis().diagnosisId());
	}

	@Test
	void detailRestoresW3DuringLocalW4AndRestoresW5OrW7() {
		material.markReady(3);
		LearningSession session = LearningSession.create(owner, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		session.applyAiTurn(
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal()),
			true
		);
		session.applyAiTurn(PageStatus.EXPLAINED, List.of(), false);
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		assertThat(sessionService.detail(1L, 100L).uiActions())
			.containsExactly(UiAction.quizProposal());

		session.applyAiTurn(
			PageStatus.REPAIR_COMPLETED,
			List.of(UiAction.moveNextPage()),
			true
		);
		assertThat(sessionService.detail(1L, 100L).uiActions())
			.containsExactly(UiAction.moveNextPage());

		session.applyAiTurn(
			PageStatus.REPAIR_COMPLETED,
			List.of(UiAction.completeSession()),
			true
		);
		assertThat(sessionService.detail(1L, 100L).uiActions())
			.containsExactly(UiAction.completeSession());
	}

	@Test
	void declinesQuizProposalAndRestoresNextLearningIdempotently() {
		material.markReady(3);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		session.moveTo(
			2,
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal())
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));

		var declined = sessionService.declineQuizProposal(1L, 100L);

		assertThat(declined).containsExactly(UiAction.moveNextPage());
		assertThat(session.getLastUiActions())
			.containsExactly(UiAction.moveNextPage());
		assertThat(session.getPageStatus()).isEqualTo(PageStatus.EXPLAINED);
		assertThat(session.getActiveQuizId()).isNull();
		verify(sessionRepository).flush();

		org.mockito.Mockito.clearInvocations(sessionRepository);
		var repeated = sessionService.declineQuizProposal(1L, 100L);
		var restored = sessionService.detail(1L, 100L);

		assertThat(repeated).containsExactly(UiAction.moveNextPage());
		assertThat(restored.uiActions())
			.containsExactly(UiAction.moveNextPage());
		assertThat(restored.uiActions()).doesNotContain(UiAction.quizProposal());
		verify(sessionRepository, never()).flush();
	}

	@Test
	void declinesLastPageQuizProposalToCompleteSession() {
		material.markReady(3);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		session.moveTo(
			3,
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal())
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		var declined = sessionService.declineQuizProposal(1L, 100L);

		assertThat(declined).containsExactly(UiAction.completeSession());
		assertThat(session.getLastUiActions())
			.containsExactly(UiAction.completeSession());
		assertThat(session.getPageStatus()).isEqualTo(PageStatus.EXPLAINED);
	}

	@Test
	void keepsNonQuizUiActionsUnchangedWhenDeclined() {
		material.markReady(3);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		session.moveTo(
			2,
			PageStatus.NOT_EXPLAINED,
			List.of(UiAction.pageExplanation())
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		var unchanged = sessionService.declineQuizProposal(1L, 100L);

		assertThat(unchanged).containsExactly(UiAction.pageExplanation());
		assertThat(session.getPageStatus())
			.isEqualTo(PageStatus.NOT_EXPLAINED);
		verify(sessionRepository, never()).flush();
	}

	@Test
	void keepsDeclineOnSamePageAndReoffersQuizAfterPageMove() {
		material.markReady(3);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		session.moveTo(
			1,
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal())
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		sessionService.declineQuizProposal(1L, 100L);

		session.applyAiTurn(PageStatus.EXPLAINED, List.of(), false);

		assertThat(session.getLastUiActions())
			.containsExactly(UiAction.moveNextPage());

		session.moveTo(
			2,
			PageStatus.NOT_EXPLAINED,
			List.of(UiAction.pageExplanation())
		);
		session.applyAiTurn(
			PageStatus.EXPLAINED,
			List.of(UiAction.quizProposal()),
			true
		);

		assertThat(session.getLastUiActions())
			.containsExactly(UiAction.quizProposal());
	}

	@Test
	void hidesInaccessibleSessionAndRejectsCompletedSession() {
		doThrow(new BusinessException(ErrorCode.SESSION_NOT_FOUND))
			.when(materialAccessService).assertSessionAccessible(2L, 100L);

		assertError(
			() -> sessionService.declineQuizProposal(2L, 100L),
			ErrorCode.SESSION_NOT_FOUND
		);
		verify(sessionRepository, never()).findOwnedForUpdate(100L, 2L);

		material.markReady(3);
		LearningSession completed = persisted(
			LearningSession.create(owner, material),
			100L
		);
		completed.complete();
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(completed));

		assertError(
			() -> sessionService.declineQuizProposal(1L, 100L),
			ErrorCode.SESSION_NOT_ACTIVE
		);
	}

	@Test
	void rejectsProcessingAndOtherOwnersMaterials() {
		when(materialAccessService.requireAccessibleForUpdate(1L, 10L))
			.thenReturn(material);

		assertThatThrownBy(() -> sessionService.create(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_PROCESSING)
			);

		when(materialAccessService.requireAccessibleForUpdate(1L, 10L))
			.thenThrow(new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		assertThatThrownBy(() -> sessionService.create(1L, 10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_NOT_FOUND)
			);
	}

	@Test
	void movesPageAndKeepsSamePageIdempotent() {
		material.markReady(3);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		var moved = sessionService.movePage(1L, 100L, 2);

		assertThat(moved.currentPage()).isEqualTo(2);
		assertThat(moved.pageStatus()).isEqualTo(PageStatus.NOT_EXPLAINED);
		assertThat(moved.uiActions().getFirst().content())
			.isEqualTo("현재 페이지를 설명할까요?");
		verify(sessionRepository).flush();

		org.mockito.Mockito.clearInvocations(sessionRepository);
		var unchanged = sessionService.movePage(1L, 100L, 2);

		assertThat(unchanged).isEqualTo(moved);
		verify(sessionRepository, never()).flush();
	}

	@Test
	void rejectsOutOfRangeInactiveAndLiveTurnMutations() {
		material.markReady(2);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		assertError(
			() -> sessionService.movePage(1L, 100L, 3),
			ErrorCode.PAGE_OUT_OF_RANGE
		);

		ReflectionTestUtils.setField(session, "activeTurnRequestId", "request-1");
		ReflectionTestUtils.setField(session, "activeTurnStartedAt", NOW);
		assertError(
			() -> sessionService.complete(1L, 100L),
			ErrorCode.SESSION_STATE_CONFLICT
		);

		ReflectionTestUtils.setField(session, "activeTurnRequestId", null);
		ReflectionTestUtils.setField(session, "activeTurnStartedAt", null);
		session.complete();
		assertError(
			() -> sessionService.movePage(1L, 100L, 1),
			ErrorCode.SESSION_NOT_ACTIVE
		);
	}

	@Test
	void startsSequentialConversationsAndClearsStaleTurn() {
		material.markReady(2);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		ReflectionTestUtils.setField(
			session,
			"activeTurnRequestId",
			"stale-request"
		);
		ReflectionTestUtils.setField(
			session,
			"activeTurnStartedAt",
			NOW.minusSeconds(301)
		);
		session.applyConversationSummary("이전 대화 요약", 90L);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));

		var first = sessionService.startNewConversation(1L, 100L);

		assertThat(first.conversationId()).isEqualTo("conversation-1");
		assertThat(first.startedAt()).isEqualTo(NOW);
		assertThat(session.getActiveTurnRequestId()).isNull();
		assertThat(session.getConversationResetAt()).isEqualTo(NOW);
		assertThat(session.getConversationResetCount()).isEqualTo(1);
		assertThat(session.getConversationSummary()).isNull();
		assertThat(session.getLastSummarizedMessageId()).isNull();

		Instant nextStartedAt = NOW.plusSeconds(1);
		SessionService nextService = new SessionService(
			sessionRepository,
			userRepository,
			new StateReducer(),
			Clock.fixed(nextStartedAt, ZoneOffset.UTC),
			diagnosisService,
			materialAccessService,
			new UiActionResolver()
		);
		var second = nextService.startNewConversation(1L, 100L);

		assertThat(second.conversationId()).isEqualTo("conversation-2");
		assertThat(second.startedAt()).isEqualTo(nextStartedAt);
		assertThat(session.getConversationResetAt()).isEqualTo(nextStartedAt);
		assertThat(session.getConversationResetCount()).isEqualTo(2);
		verify(sessionRepository, times(2)).flush();
	}

	@Test
	void rejectsNewConversationForLiveTurnInactiveDeletedAndMissingSession() {
		material.markReady(2);
		LearningSession session = persisted(
			LearningSession.create(owner, material),
			100L
		);
		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.of(session));
		ReflectionTestUtils.setField(
			session,
			"activeTurnRequestId",
			"live-request"
		);
		ReflectionTestUtils.setField(session, "activeTurnStartedAt", NOW);

		assertError(
			() -> sessionService.startNewConversation(1L, 100L),
			ErrorCode.SESSION_STATE_CONFLICT
		);

		session.complete();
		assertError(
			() -> sessionService.startNewConversation(1L, 100L),
			ErrorCode.SESSION_NOT_ACTIVE
		);

		session.delete();
		assertError(
			() -> sessionService.startNewConversation(1L, 100L),
			ErrorCode.SESSION_NOT_FOUND
		);

		when(sessionRepository.findOwnedForUpdate(100L, 1L))
			.thenReturn(Optional.empty());
		assertError(
			() -> sessionService.startNewConversation(1L, 100L),
			ErrorCode.SESSION_NOT_FOUND
		);
	}

	private LearningSession persisted(LearningSession session, Long id) {
		ReflectionTestUtils.setField(session, "id", id);
		ReflectionTestUtils.setField(session, "updatedAt", NOW);
		return session;
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
