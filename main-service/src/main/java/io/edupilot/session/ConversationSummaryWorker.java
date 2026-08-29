package io.edupilot.session;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.ConversationSummaryResponse;
import io.edupilot.global.security.TraceIdFilter;

@Component
public class ConversationSummaryWorker implements ConversationSummaryTask {

	private static final Logger log = LoggerFactory.getLogger(
		ConversationSummaryWorker.class
	);

	private final ConversationSummaryPersistenceService persistenceService;
	private final AiClient aiClient;

	public ConversationSummaryWorker(
		ConversationSummaryPersistenceService persistenceService,
		AiClient aiClient
	) {
		this.persistenceService = persistenceService;
		this.aiClient = aiClient;
	}

	@Override
	public void summarize(Long sessionId, String traceId) {
		Map<String, String> previousContext = MDC.getCopyOfContextMap();
		MDC.put(
			TraceIdFilter.TRACE_ID_MDC_KEY,
			traceId == null || traceId.isBlank()
				? UUID.randomUUID().toString()
				: traceId
		);
		try {
			Optional<ConversationSummaryBatch> candidate =
				persistenceService.prepare(sessionId);
			if (candidate.isEmpty()) {
				return;
			}
			ConversationSummaryBatch batch = candidate.get();
			ConversationSummaryResponse response =
				aiClient.summarizeConversation(
					batch.previousSummary(),
					batch.messages()
				);
			boolean applied = persistenceService.apply(
				batch,
				response.summary()
			);
			log.atInfo()
				.addKeyValue("sessionId", sessionId)
				.addKeyValue("messageCount", batch.messages().size())
				.addKeyValue("characterCount", batch.characterCount())
				.addKeyValue("summaryLength", response.summary().length())
				.addKeyValue("applied", applied)
				.log("Conversation summary worker completed");
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("sessionId", sessionId)
				.addKeyValue(
					"errorType",
					exception.getClass().getSimpleName()
				)
				.log("Conversation summary worker failed");
		} finally {
			if (previousContext == null) {
				MDC.clear();
			} else {
				MDC.setContextMap(previousContext);
			}
		}
	}
}
