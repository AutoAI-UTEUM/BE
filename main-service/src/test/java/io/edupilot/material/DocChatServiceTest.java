package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.DocChatRequest.ContextDocument;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.dto.DocChatRequest;
import io.edupilot.quiz.QuizDocChatContextService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class DocChatServiceTest {

	@Mock
	private AiClient aiClient;
	@Mock
	private AiUsageService aiUsageService;
	@Mock
	private AiQuotaService aiQuotaService;
	@Mock
	private UserRepository userRepository;

	@Mock
	private MaterialDocChatContextService materialContextService;

	@Mock
	private QuizDocChatContextService quizContextService;

	@BeforeEach
	void setUpUser() {
		when(userRepository.findById(1L)).thenReturn(Optional.of(
			User.create("learner@example.com", "hash", "학습자")
		));
	}

	@Test
	void keepsOnlyLatestTenHistoryMessagesAndReturnsWarnings() {
		AiUsage usage = new AiUsage("grok-doc-chat", 11L, 7L, 2L);
		List<ContextDocument> documents = List.of(
			new ContextDocument("material p.1-1", "page text")
		);
		when(materialContextService.build(1L, 10L)).thenReturn(documents);
		when(aiClient.docChat(any())).thenReturn(
			new io.edupilot.ai.dto.DocChatResponse(
				"1.0",
				"answer",
				List.of(new io.edupilot.ai.dto.DocChatResponse.Warning(
					"CONTEXT_TRUNCATED",
					"context was truncated"
				)),
				usage
			)
		);
		List<DocChatRequest.HistoryMessage> history = IntStream.range(0, 12)
			.mapToObj(index -> new DocChatRequest.HistoryMessage(
				index % 2 == 0
					? DocChatRequest.Role.USER
					: DocChatRequest.Role.ASSISTANT,
				"message-" + index
			))
			.toList();
		DocChatService service = service();

		var response = service.askMaterial(
			1L,
			10L,
			new DocChatRequest(" question ", history)
		);

		ArgumentCaptor<io.edupilot.ai.dto.DocChatRequest> captor =
			ArgumentCaptor.forClass(io.edupilot.ai.dto.DocChatRequest.class);
		verify(aiClient).docChat(captor.capture());
		assertThat(captor.getValue().contextDocs()).isEqualTo(documents);
		assertThat(captor.getValue().history()).hasSize(10);
		assertThat(captor.getValue().history().getFirst().content())
			.isEqualTo("message-2");
		assertThat(captor.getValue().history().getLast().content())
			.isEqualTo("message-11");
		assertThat(captor.getValue().question()).isEqualTo("question");
		assertThat(response.answer()).isEqualTo("answer");
		assertThat(response.warnings()).singleElement()
			.extracting(io.edupilot.material.dto.DocChatResponse.Warning::type)
			.isEqualTo("CONTEXT_TRUNCATED");
		verify(aiUsageService).record(1L, AiFeature.DOC_CHAT, usage, true);
	}

	@Test
	void propagatesMappedAiFailureWithoutChangingIt() {
		when(quizContextService.build(1L, 10L)).thenReturn(List.of(
			new ContextDocument("quiz", "review")
		));
		when(aiClient.docChat(any())).thenThrow(
			new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT)
		);

		assertThatThrownBy(() -> service().askQuiz(
			1L,
			10L,
			new DocChatRequest("question", List.of())
		)).isInstanceOfSatisfying(AiClientException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
		);
		verify(aiUsageService).record(1L, AiFeature.DOC_CHAT, null, false);
	}

	private DocChatService service() {
		return new DocChatService(
			aiClient,
			aiUsageService,
			aiQuotaService,
			userRepository,
			materialContextService,
			quizContextService
		);
	}
}
