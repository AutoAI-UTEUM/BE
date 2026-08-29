package io.edupilot.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.ai.dto.ConversationSummaryMessage;

@Service
public class ConversationSummaryPersistenceService {

	static final int MESSAGE_LIMIT = 20;

	private final LearningSessionRepository sessionRepository;
	private final ChatMessageRepository messageRepository;
	private final ConversationSummaryProperties properties;

	public ConversationSummaryPersistenceService(
		LearningSessionRepository sessionRepository,
		ChatMessageRepository messageRepository,
		ConversationSummaryProperties properties
	) {
		this.sessionRepository = sessionRepository;
		this.messageRepository = messageRepository;
		this.properties = properties;
	}

	@Transactional(readOnly = true)
	public Optional<ConversationSummaryBatch> prepare(Long sessionId) {
		LearningSession session = sessionRepository.findById(sessionId)
			.filter(value -> value.getStatus() == SessionStatus.ACTIVE)
			.orElse(null);
		if (session == null) {
			return Optional.empty();
		}

		Long previousMarker = session.getLastSummarizedMessageId();
		long completedUserMessages =
			messageRepository.countCompletedUserMessagesAfterBoundary(
				sessionId,
				previousMarker,
				session.getConversationResetAt()
			);
		if (completedUserMessages < properties.turnInterval()) {
			return Optional.empty();
		}

		List<ChatMessage> messages =
			messageRepository.findCompletedMessagesAfterBoundary(
				sessionId,
				previousMarker,
				session.getConversationResetAt(),
				PageRequest.of(0, MESSAGE_LIMIT)
			);
		if (messages.isEmpty()) {
			return Optional.empty();
		}

		List<ConversationSummaryMessage> requestMessages = messages.stream()
			.map(message -> new ConversationSummaryMessage(
				message.getSenderType() == SenderType.USER
					? "USER"
					: "ASSISTANT",
				message.getContent()
			))
			.toList();
		int characterCount = requestMessages.stream()
			.mapToInt(message -> message.content().length())
			.sum();
		return Optional.of(new ConversationSummaryBatch(
			sessionId,
			session.getConversationSummary(),
			previousMarker,
			session.getConversationResetAt(),
			messages.getLast().getId(),
			requestMessages,
			characterCount
		));
	}

	@Transactional
	public boolean apply(
		ConversationSummaryBatch batch,
		String summary
	) {
		LearningSession session = sessionRepository
			.findForConversationSummaryUpdate(batch.sessionId())
			.orElse(null);
		if (session == null
			|| session.getStatus() != SessionStatus.ACTIVE
			|| !Objects.equals(
				session.getLastSummarizedMessageId(),
				batch.previousLastSummarizedMessageId()
			)
			|| !Objects.equals(
				session.getConversationResetAt(),
				batch.conversationResetAt()
			)
			|| !Objects.equals(
				session.getConversationSummary(),
				batch.previousSummary()
			)) {
			return false;
		}
		session.applyConversationSummary(
			summary,
			batch.summarizedThroughMessageId()
		);
		sessionRepository.flush();
		return true;
	}
}
