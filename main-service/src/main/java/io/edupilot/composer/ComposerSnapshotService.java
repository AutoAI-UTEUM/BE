package io.edupilot.composer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.EmailVerificationGate;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.*;

@Service
@ConditionalOnProperty(name = "edupilot.composer.enabled", havingValue = "true")
public class ComposerSnapshotService {
	private static final Set<String> SAFE_UI = Set.of("MOVE_NEXT_PAGE", "COMPLETE_SESSION", "NOTE_REQUESTED");
	private final LearningSessionRepository sessions;
	private final ChatMessageRepository messages;
	private final SessionStreamAccessGuard access;
	private final EmailVerificationGate email;

	public ComposerSnapshotService(LearningSessionRepository sessions, ChatMessageRepository messages,
		SessionStreamAccessGuard access, EmailVerificationGate email) {
		this.sessions = sessions;
		this.messages = messages;
		this.access = access;
		this.email = email;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Snapshot capture(Long userId, Long sessionId, Long sourceId, SessionStreamAccessGuard.Access grant) {
		access.assertAccessible(userId, sessionId, grant);
		email.requireAiVerified(userId);
		LearningSession session = sessions.findByIdAndUser_Id(sessionId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		if (session.getStatus() != SessionStatus.ACTIVE
			|| session.getActiveTurnRequestId() != null
			|| session.getActiveQuizId() != null || session.getPendingDiagnosisId() != null
			|| session.getPageStatus() != PageStatus.EXPLAINED
			|| session.getLastUiActions().stream().anyMatch(a -> !safeUi(a))) {
			return null;
		}
		var latest = messages.findBySession_IdOrderByCreatedAtDescIdDesc(sessionId, PageRequest.of(0, 1));
		if (latest.isEmpty()) return null;
		ChatMessage source = latest.getFirst();
		if (!sourceId.equals(source.getId()) || source.getSenderType() != SenderType.AI
			|| source.getContent() == null || source.getContent().isBlank()
			|| source.getStatus() != ChatMessageStatus.COMPLETED
			|| source.getPageNumber() != session.getCurrentPage()
			|| (source.getMessageType() != MessageType.EXPLANATION && source.getMessageType() != MessageType.QA)
			|| (session.getConversationResetAt() != null
				&& !source.getCreatedAt().isAfter(session.getConversationResetAt()))) return null;
		var recent = messages.findComposerMessages(sessionId, session.getCurrentPage(),
			session.getConversationResetAt(), PageRequest.of(0, 6));
		var publicMessages = boundedMessages(recent);
		if (publicMessages.isEmpty() || !recent.getFirst().getId().equals(sourceId)
			|| publicMessages.getLast().role() != ComposerAiRequest.Role.ASSISTANT) return null;
		String version = digest(sessionId + ":" + session.getVersion() + ":" + sourceId
			+ ":" + session.getCurrentPage() + ":" + session.getConversationResetCount());
		var activity = source.getMessageType() == MessageType.EXPLANATION
			? ComposerAiRequest.Activity.EXPLAIN : ComposerAiRequest.Activity.QA;
		return new Snapshot(userId, sessionId, sourceId, new ComposerAiRequest("1.0", "message-" + sourceId,
			version, activity, publicMessages));
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public boolean fresh(Snapshot expected, SessionStreamAccessGuard.Access grant) {
		Snapshot current = capture(expected.userId(), expected.sessionId(), expected.sourceMessageId(), grant);
		return current != null && current.request().equals(expected.request());
	}

	// Unknown/new actions, including any future TEACHBACK action, are denied by default.
	private static boolean safeUi(UiAction action) {
		return action != null && "BINARY_DECISION".equals(action.type())
			&& action.yesEvent() != null && SAFE_UI.contains(action.yesEvent()) && "WAIT".equals(action.noEvent())
			&& action.diagnosisId() == null;
	}

	static List<ComposerAiRequest.Message> boundedMessages(List<ChatMessage> newestFirst) {
		var result = new ArrayList<ComposerAiRequest.Message>();
		int remaining = 6000;
		for (ChatMessage message : newestFirst.stream().limit(6).toList()) {
			String text = message.getContent();
			if (text == null || text.isBlank()) continue;
			int count = text.codePointCount(0, text.length());
			int keep = Math.min(remaining, Math.min(4000, count));
			if (keep == 0) break;
			text = text.substring(text.offsetByCodePoints(0, count - keep));
			result.add(new ComposerAiRequest.Message(message.getSenderType() == SenderType.AI
				? ComposerAiRequest.Role.ASSISTANT : ComposerAiRequest.Role.USER, text));
			remaining -= keep;
		}
		Collections.reverse(result);
		return List.copyOf(result);
	}

	static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	public record Snapshot(Long userId, Long sessionId, Long sourceMessageId, ComposerAiRequest request) {
		public String reservationId() {
			// Stronger than context-only dedup: no regeneration of the same saved answer,
			// even if a summary update advances the session revision.
			return digest("composer:" + userId + ":" + sessionId + ":" + sourceMessageId);
		}
	}
}
