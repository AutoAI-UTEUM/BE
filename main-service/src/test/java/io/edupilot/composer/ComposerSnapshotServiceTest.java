package io.edupilot.composer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.EmailVerificationGate;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.*;
import io.edupilot.user.UserRole;

class ComposerSnapshotServiceTest {
	final LearningSessionRepository sessions = mock(LearningSessionRepository.class);
	final ChatMessageRepository messages = mock(ChatMessageRepository.class);
	final SessionStreamAccessGuard access = mock(SessionStreamAccessGuard.class);
	final EmailVerificationGate email = mock(EmailVerificationGate.class);
	final LearningSession session = mock(LearningSession.class);
	final SessionStreamAccessGuard.Access grant = new SessionStreamAccessGuard.Access(UserRole.LEARNER, 4);
	final ComposerSnapshotService service = new ComposerSnapshotService(sessions, messages, access, email);
	ChatMessage source;

	@BeforeEach void setup() {
		when(sessions.findByIdAndUser_Id(10L, 1L)).thenReturn(Optional.of(session));
		when(session.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(session.getPageStatus()).thenReturn(PageStatus.EXPLAINED);
		when(session.getCurrentPage()).thenReturn(2);
		when(session.getLastUiActions()).thenReturn(List.of(UiAction.moveNextPage()));
		when(session.getVersion()).thenReturn(7L);
		when(session.getActiveQuizId()).thenReturn(null);
		when(session.getPendingDiagnosisId()).thenReturn(null);
		source = message(50, SenderType.AI, MessageType.EXPLANATION, "Public explanation");
		when(messages.findBySession_IdOrderByCreatedAtDescIdDesc(eq(10L), any())).thenReturn(List.of(source));
		when(messages.findComposerMessages(eq(10L), eq(2), isNull(), any())).thenReturn(List.of(source));
	}

	@Test void buildsOnlyFromCommittedServerMessages() {
		var snapshot = capture();
		assertThat(snapshot.request().sourceTurnId()).isEqualTo("message-50");
		assertThat(snapshot.request().learningActivity()).isEqualTo(ComposerAiRequest.Activity.EXPLAIN);
		assertThat(snapshot.request().contextVersion()).hasSize(64);
		assertThat(snapshot.request().messages()).containsExactly(new ComposerAiRequest.Message(
			ComposerAiRequest.Role.ASSISTANT, "Public explanation"));
		verify(access).assertAccessible(1L, 10L, grant);
		verify(email).requireAiVerified(1L);
	}

	@Test void ownershipFailureCannotBecomeOptionalSuccess() {
		when(sessions.findByIdAndUser_Id(10L, 1L)).thenReturn(Optional.empty());
		assertThatThrownBy(this::capture).isInstanceOf(BusinessException.class);
		verifyNoInteractions(messages);
	}

	@Test void revokedGuardianConsentAndEmailGateAreChecked() {
		doThrow(new BusinessException(ErrorCode.GUARDIAN_AI_CONSENT_REQUIRED)).when(email).requireAiVerified(1L);
		assertThatThrownBy(this::capture).isInstanceOf(BusinessException.class);
		verifyNoInteractions(messages);
	}

	@ParameterizedTest @EnumSource(value = PageStatus.class, names = "EXPLAINED", mode = EnumSource.Mode.EXCLUDE)
	void assessmentOrUnknownPageStateNeverCallsAi(PageStatus state) {
		when(session.getPageStatus()).thenReturn(state);
		assertThat(capture()).isNull();
		verifyNoInteractions(messages);
	}

	@Test void activeQuizDiagnosisClaimAndTeachbackActionAreDenied() {
		when(session.getActiveQuizId()).thenReturn(4L); assertThat(capture()).isNull();
		when(session.getActiveQuizId()).thenReturn(null);
		when(session.getPendingDiagnosisId()).thenReturn(8L); assertThat(capture()).isNull();
		when(session.getPendingDiagnosisId()).thenReturn(null);
		when(session.getActiveTurnRequestId()).thenReturn("in-flight"); assertThat(capture()).isNull();
		when(session.getActiveTurnRequestId()).thenReturn(null);
		when(session.getLastUiActions()).thenReturn(List.of(new UiAction("TEACHBACK", "x", null, null, null)));
		assertThat(capture()).isNull();
	}

	@Test void quizProposalIsConservativelySuppressedToo() {
		when(session.getLastUiActions()).thenReturn(List.of(UiAction.quizProposal()));
		assertThat(capture()).isNull();
	}

	@Test void missingUiEventAndBlankSourceAreUnavailableWithoutAnException() {
		when(session.getLastUiActions()).thenReturn(List.of(new UiAction("BINARY_DECISION", "x", null, "WAIT", null)));
		assertThat(capture()).isNull();
		when(session.getLastUiActions()).thenReturn(List.of(UiAction.moveNextPage()));
		ReflectionTestUtils.setField(source, "content", "  ");
		assertThat(capture()).isNull();
	}

	@ParameterizedTest @EnumSource(value = MessageType.class, names = {"EXPLANATION", "QA"}, mode = EnumSource.Mode.EXCLUDE)
	void nonPublicOrAssessmentAndCancelledTextCannotBeSource(MessageType type) {
		ReflectionTestUtils.setField(source, "messageType", type);
		assertThat(capture()).isNull();
	}

	@Test void failedForeignPageOldConversationOrNonLatestSourcesAreRejected() {
		source.markFailed(); assertThat(capture()).isNull(); source.retry();
		ReflectionTestUtils.setField(source, "pageNumber", 3); assertThat(capture()).isNull();
		ReflectionTestUtils.setField(source, "pageNumber", 2);
		when(session.getConversationResetAt()).thenReturn(Instant.parse("2026-11-04T01:00:00Z"));
		assertThat(capture()).isNull();
		when(session.getConversationResetAt()).thenReturn(null);
		assertThat(service.capture(1L, 10L, 49L, grant)).isNull();
	}

	@Test void latestMessageMustBeAssistantAndSafeQueryMustContainSource() {
		ReflectionTestUtils.setField(source, "senderType", SenderType.USER); assertThat(capture()).isNull();
		ReflectionTestUtils.setField(source, "senderType", SenderType.AI);
		when(messages.findComposerMessages(eq(10L), eq(2), isNull(), any())).thenReturn(List.of());
		assertThat(capture()).isNull();
	}

	@Test void serverRevisionAndMessagesControlFreshness() {
		var snapshot = capture();
		assertThat(service.fresh(snapshot, grant)).isTrue();
		when(session.getVersion()).thenReturn(8L);
		assertThat(service.fresh(snapshot, grant)).isFalse();
		// Same saved answer cannot be regenerated by advancing the revision.
		assertThat(capture().reservationId()).isEqualTo(snapshot.reservationId());
	}

	@Test void contextIsAtMostSixMessages4000Each6000TotalChronologicalAndUnicodeSafe() {
		var input = IntStream.range(0, 8).mapToObj(i -> message(100 - i, SenderType.AI,
			MessageType.QA, "🙂".repeat(4100))).toList();
		var bounded = ComposerSnapshotService.boundedMessages(input);
		assertThat(bounded).hasSize(2);
		assertThat(bounded.stream().mapToInt(m -> m.content().codePointCount(0, m.content().length())).sum()).isEqualTo(6000);
		assertThat(bounded.getFirst().content().codePointCount(0, bounded.getFirst().content().length())).isEqualTo(2000);
		var small = IntStream.range(0, 8).mapToObj(i -> message(100 - i, SenderType.AI, MessageType.QA, "m" + i)).toList();
		assertThat(ComposerSnapshotService.boundedMessages(small)).extracting(ComposerAiRequest.Message::content)
			.containsExactly("m5", "m4", "m3", "m2", "m1", "m0");
	}

	ComposerSnapshotService.Snapshot capture() { return service.capture(1L, 10L, 50L, grant); }
	static ChatMessage message(long id, SenderType sender, MessageType type, String content) {
		var message = ChatMessage.ai(mock(LearningSession.class), type, content);
		ReflectionTestUtils.setField(message, "id", id);
		ReflectionTestUtils.setField(message, "senderType", sender);
		ReflectionTestUtils.setField(message, "pageNumber", 2);
		ReflectionTestUtils.setField(message, "createdAt", Instant.parse("2026-11-04T00:00:00Z"));
		return message;
	}
}
