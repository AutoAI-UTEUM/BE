package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class ConversationSummaryPersistenceServiceTest {

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private ChatMessageRepository messageRepository;

	@Test
	void waitsAtSevenUserTurnsAndPreparesAtEight() {
		LearningSession session = session();
		when(sessionRepository.findById(100L))
			.thenReturn(Optional.of(session));
		when(messageRepository.countCompletedUserMessagesAfterBoundary(
			100L, null, null
		)).thenReturn(7L, 8L);

		ConversationSummaryPersistenceService service = service();
		assertThat(service.prepare(100L)).isEmpty();
		verify(messageRepository, never())
			.findCompletedMessagesAfterBoundary(
				eq(100L),
				eq(null),
				eq(null),
				any(Pageable.class)
			);

		List<ChatMessage> messages = messages(session, 1, 16);
		when(messageRepository.findCompletedMessagesAfterBoundary(
			eq(100L),
			eq(null),
			eq(null),
			any(Pageable.class)
		)).thenReturn(messages);

		ConversationSummaryBatch batch = service.prepare(100L).orElseThrow();
		assertThat(batch.messages()).hasSize(16);
		assertThat(batch.messages())
			.extracting(message -> message.role())
			.containsExactly(
				"USER", "ASSISTANT", "USER", "ASSISTANT",
				"USER", "ASSISTANT", "USER", "ASSISTANT",
				"USER", "ASSISTANT", "USER", "ASSISTANT",
				"USER", "ASSISTANT", "USER", "ASSISTANT"
			);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(
			Pageable.class
		);
		verify(messageRepository).findCompletedMessagesAfterBoundary(
			eq(100L),
			eq(null),
			eq(null),
			pageable.capture()
		);
		assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
	}

	@Test
	void advancesOnlyThroughOldestTwentyAndDefersOverflow() {
		LearningSession session = session();
		List<ChatMessage> allMessages = messages(session, 1, 36);
		List<ChatMessage> firstBatch = allMessages.subList(0, 20);
		List<ChatMessage> deferredBatch = allMessages.subList(20, 36);
		when(sessionRepository.findById(100L))
			.thenReturn(Optional.of(session));
		when(sessionRepository.findForConversationSummaryUpdate(100L))
			.thenReturn(Optional.of(session));
		when(messageRepository.countCompletedUserMessagesAfterBoundary(
			eq(100L), nullable(Long.class), eq(null)
		)).thenReturn(8L);
		when(messageRepository.findCompletedMessagesAfterBoundary(
			eq(100L), nullable(Long.class), eq(null), any(Pageable.class)
		)).thenReturn(firstBatch, deferredBatch);

		ConversationSummaryPersistenceService service = service();
		ConversationSummaryBatch first = service.prepare(100L).orElseThrow();
		assertThat(first.messages()).hasSize(20);
		assertThat(first.summarizedThroughMessageId()).isEqualTo(20L);
		assertThat(service.apply(first, "첫 요약")).isTrue();

		ConversationSummaryBatch second = service.prepare(100L).orElseThrow();
		assertThat(second.previousLastSummarizedMessageId()).isEqualTo(20L);
		assertThat(second.previousSummary()).isEqualTo("첫 요약");
		assertThat(second.messages()).hasSize(16);
		assertThat(second.summarizedThroughMessageId()).isEqualTo(36L);
	}

	private ConversationSummaryPersistenceService service() {
		return new ConversationSummaryPersistenceService(
			sessionRepository,
			messageRepository,
			new ConversationSummaryProperties(8)
		);
	}

	private LearningSession session() {
		User user = User.create("summary@example.com", "hash", "학습자");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/summary.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		LearningSession session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 100L);
		return session;
	}

	private List<ChatMessage> messages(
		LearningSession session,
		int firstId,
		int lastId
	) {
		return IntStream.rangeClosed(firstId, lastId)
			.mapToObj(id -> {
				ChatMessage message = id % 2 == 1
					? ChatMessage.user(session, "질문 " + id, "request-" + id)
					: ChatMessage.ai(session, "답변 " + id);
				ReflectionTestUtils.setField(message, "id", (long) id);
				return message;
			})
			.toList();
	}
}
