package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.ConversationSummaryMessage;
import io.edupilot.ai.dto.ConversationSummaryResponse;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

@ExtendWith(MockitoExtension.class)
class ConversationSummaryWorkerTest {

	@Mock
	private ConversationSummaryPersistenceService persistenceService;
	@Mock
	private AiClient aiClient;

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void skipsAiCallBeforeTriggerBoundary() {
		when(persistenceService.prepare(100L)).thenReturn(Optional.empty());

		worker().summarize(100L, "trace-summary");

		verify(aiClient, never()).summarizeConversation(any(), any());
	}

	@Test
	void failureLeavesBoundaryUntouchedAndNextDispatchRetries() {
		ConversationSummaryBatch batch = batch();
		when(persistenceService.prepare(100L))
			.thenReturn(Optional.of(batch));
		when(aiClient.summarizeConversation(any(), any()))
			.thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT))
			.thenReturn(new ConversationSummaryResponse(
				"1.0",
				"재시도 요약"
			));

		assertThatCode(() -> worker().summarize(100L, "trace-summary"))
			.doesNotThrowAnyException();
		verify(persistenceService, never()).apply(any(), any());

		worker().summarize(100L, "trace-summary");

		verify(aiClient, times(2)).summarizeConversation(
			eq("이전 요약"),
			eq(batch.messages())
		);
		verify(persistenceService).apply(batch, "재시도 요약");
	}

	@Test
	void propagatesTraceIdInsideWorkerAndRestoresCallerMdc() {
		ConversationSummaryBatch batch = batch();
		when(persistenceService.prepare(100L))
			.thenReturn(Optional.of(batch));
		when(aiClient.summarizeConversation(any(), any()))
			.thenAnswer(invocation -> {
				assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY))
					.isEqualTo("turn-trace-337");
				return new ConversationSummaryResponse("1.0", "새 요약");
			});
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "caller-context");

		worker().summarize(100L, "turn-trace-337");

		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY))
			.isEqualTo("caller-context");
	}

	private ConversationSummaryWorker worker() {
		return new ConversationSummaryWorker(persistenceService, aiClient);
	}

	private ConversationSummaryBatch batch() {
		return new ConversationSummaryBatch(
			100L,
			"이전 요약",
			20L,
			null,
			36L,
			List.of(
				new ConversationSummaryMessage("USER", "새 질문"),
				new ConversationSummaryMessage("ASSISTANT", "새 답변")
			),
			8
		);
	}
}
