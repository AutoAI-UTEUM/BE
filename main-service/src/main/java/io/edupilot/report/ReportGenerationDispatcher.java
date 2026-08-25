package io.edupilot.report;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReportGenerationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(
		ReportGenerationDispatcher.class
	);

	private final Executor executor;
	private final ObjectProvider<ReportGenerationTask> workerProvider;

	public ReportGenerationDispatcher(
		@Qualifier("reportGenerationExecutor") Executor executor,
		ObjectProvider<ReportGenerationTask> workerProvider
	) {
		this.executor = executor;
		this.workerProvider = workerProvider;
	}

	public void dispatchAfterCommit(Long generationId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException(
				"Report generation dispatch requires a transaction"
			);
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					dispatch(generationId);
				}
			}
		);
	}

	public void dispatch(Long generationId) {
		try {
			executor.execute(
				() -> workerProvider.getObject().generate(generationId)
			);
		} catch (TaskRejectedException exception) {
			log.atWarn()
				.addKeyValue("generationId", generationId)
				.log("Report generation executor rejected generation");
		}
	}
}
