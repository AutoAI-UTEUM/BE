package io.edupilot.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.dto.MessageListResponse;
import io.edupilot.session.dto.MessageResponse;

@Service
public class SessionMessageService {

	private final LearningSessionRepository sessionRepository;
	private final ChatMessageRepository messageRepository;
	private final MessageCursorCodec cursorCodec;

	public SessionMessageService(
		LearningSessionRepository sessionRepository,
		ChatMessageRepository messageRepository,
		MessageCursorCodec cursorCodec
	) {
		this.sessionRepository = sessionRepository;
		this.messageRepository = messageRepository;
		this.cursorCodec = cursorCodec;
	}

	@Transactional(readOnly = true)
	public MessageListResponse messages(
		Long userId,
		Long sessionId,
		String cursor,
		int size
	) {
		sessionRepository.findByIdAndUser_Id(sessionId, userId)
			.filter(session -> session.getStatus() != SessionStatus.DELETED)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

		PageRequest limit = PageRequest.of(0, size + 1);
		List<ChatMessage> descending;
		if (cursor == null || cursor.isBlank()) {
			descending = messageRepository
				.findBySession_IdOrderByCreatedAtDescIdDesc(sessionId, limit);
		} else {
			MessageCursorCodec.Cursor decoded = cursorCodec.decode(cursor);
			descending = messageRepository.findOlderThan(
				sessionId,
				decoded.createdAt(),
				decoded.messageId(),
				limit
			);
		}

		boolean hasMore = descending.size() > size;
		List<ChatMessage> page = new ArrayList<>(
			descending.subList(0, Math.min(size, descending.size()))
		);
		String nextCursor = hasMore && !page.isEmpty()
			? cursorCodec.encode(
				page.getLast().getCreatedAt(),
				page.getLast().getId()
			)
			: null;
		Collections.reverse(page);
		List<MessageResponse> items = page.stream()
			.map(MessageResponse::from)
			.toList();
		return new MessageListResponse(items, nextCursor, hasMore);
	}
}
