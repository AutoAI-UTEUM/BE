package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExamGradingDispatcherTest {

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void dispatchesOnlyAfterTransactionCommit() {
		Executor executor = mock(Executor.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<ExamGradingWorker> workerProvider = mock(ObjectProvider.class);
		ExamGradingDispatcher dispatcher = new ExamGradingDispatcher(executor, workerProvider);
		TransactionSynchronizationManager.initSynchronization();

		dispatcher.dispatchAfterCommit(10L, 20L);

		verify(executor, never()).execute(org.mockito.ArgumentMatchers.any());
		assertThat(TransactionSynchronizationManager.getSynchronizations()).singleElement()
			.satisfies(synchronization -> synchronization.afterCommit());
		verify(executor).execute(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void createsBoundedAbortPolicyExecutor() {
		ExamGradingProperties properties = new ExamGradingProperties(
			Duration.ofMinutes(5),
			new ExamGradingProperties.Executor(2, 4, 100)
		);
		ThreadPoolTaskExecutor executor = new ExamGradingConfig()
			.examGradingExecutor(properties);

		assertThat(executor.getCorePoolSize()).isEqualTo(2);
		assertThat(executor.getMaxPoolSize()).isEqualTo(4);
		assertThat(executor.getQueueCapacity()).isEqualTo(100);
		assertThat(executor.getThreadNamePrefix()).isEqualTo("exam-grading-");
		assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
			.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);

		executor.shutdown();
	}
}
