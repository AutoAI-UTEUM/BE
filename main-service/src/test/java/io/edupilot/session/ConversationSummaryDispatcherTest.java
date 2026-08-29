package io.edupilot.session;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.edupilot.global.security.TraceIdFilter;

class ConversationSummaryDispatcherTest {

	@AfterEach
	void cleanUp() {
		MDC.clear();
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void dispatchesAfterCommitWithCapturedTraceId() {
		AtomicReference<Runnable> submitted = new AtomicReference<>();
		Executor executor = submitted::set;
		ConversationSummaryTask task = mock(ConversationSummaryTask.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<ConversationSummaryTask> provider = mock(
			ObjectProvider.class
		);
		when(provider.getObject()).thenReturn(task);
		ConversationSummaryDispatcher dispatcher =
			new ConversationSummaryDispatcher(executor, provider);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-after-commit");
		TransactionSynchronizationManager.initSynchronization();

		dispatcher.dispatchAfterCommit(100L);
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(synchronization -> synchronization.afterCommit());
		MDC.clear();
		submitted.get().run();

		verify(task).summarize(100L, "trace-after-commit");
	}
}
