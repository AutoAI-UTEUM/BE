package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class SessionMessageServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private ChatMessageRepository messageRepository;

	private SessionMessageService messageService;
	private LearningSession session;

	@BeforeEach
	void setUp() {
		messageService = new SessionMessageService(
			sessionRepository,
			messageRepository,
			new MessageCursorCodec()
		);
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/test.pdf"
		);
		session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 100L);
	}

	@Test
	void returnsLatestPageInAscendingOrderWithCompositeNextCursor() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		ChatMessage newest = message(3L, "2026-07-25T10:00:03Z");
		ChatMessage middle = message(2L, "2026-07-25T10:00:02Z");
		ChatMessage older = message(1L, "2026-07-25T10:00:01Z");
		when(messageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
			org.mockito.ArgumentMatchers.eq(100L),
			any()
		)).thenReturn(List.of(newest, middle, older));

		var response = messageService.messages(1L, 100L, null, 2);

		assertThat(response.items())
			.extracting(item -> item.messageId())
			.containsExactly(2L, 3L);
		assertThat(response.hasMore()).isTrue();
		MessageCursorCodec.Cursor cursor = new MessageCursorCodec()
			.decode(response.nextCursor());
		assertThat(cursor.messageId()).isEqualTo(2L);
	}

	@Test
	void completedIsReadableButDeletedIsHidden() {
		session.complete();
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository.findBySession_IdOrderByCreatedAtDescIdDesc(
			org.mockito.ArgumentMatchers.eq(100L),
			any()
		)).thenReturn(List.of());

		assertThat(messageService.messages(1L, 100L, null, 30).items())
			.isEmpty();

		session.delete();
		assertThatThrownBy(() -> messageService.messages(1L, 100L, null, 30))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.SESSION_NOT_FOUND)
			);
	}

	private ChatMessage message(Long id, String createdAt) {
		ChatMessage message = ChatMessage.ai(session, "응답 " + id);
		ReflectionTestUtils.setField(message, "id", id);
		ReflectionTestUtils.setField(
			message,
			"createdAt",
			Instant.parse(createdAt)
		);
		return message;
	}
}
