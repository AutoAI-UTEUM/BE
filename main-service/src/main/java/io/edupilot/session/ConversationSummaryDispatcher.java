package io.edupilot.session;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.edupilot.global.security.TraceIdFilter;

@Component
public class ConversationSummaryDispatcher {

	private static final Logger log = LoggerFactory.getLogger(
		ConversationSummaryDispatcher.class
	);

	private final Executor executor;
	private final ObjectProvider<ConversationSummaryTask> workerProvider;

	public ConversationSummaryDispatcher(
		@Qualifier("conversationSummaryExecutor") Executor executor,
		ObjectProvider<ConversationSummaryTask> workerProvider
	) {
		this.executor = executor;
		this.workerProvider = workerProvider;
	}

	public void dispatchAfterCommit(Long sessionId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException(
				"Conversation summary dispatch requires a transaction"
			);
		}
		String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					dispatch(sessionId, traceId);
				}
			}
		);
	}

	void dispatch(Long sessionId, String traceId) {
		try {
			executor.execute(
				() -> workerProvider.getObject().summarize(sessionId, traceId)
			);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("sessionId", sessionId)
				.addKeyValue(
					"errorType",
					exception.getClass().getSimpleName()
				)
				.log("Conversation summary executor rejected task");
		}
	}
}
