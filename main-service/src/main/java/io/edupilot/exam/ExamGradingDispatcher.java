package io.edupilot.exam;

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
public class ExamGradingDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ExamGradingDispatcher.class);

	private final Executor executor;
	private final ObjectProvider<ExamGradingWorker> workerProvider;

	public ExamGradingDispatcher(
		@Qualifier("examGradingExecutor") Executor executor,
		ObjectProvider<ExamGradingWorker> workerProvider
	) {
		this.executor = executor;
		this.workerProvider = workerProvider;
	}

	public void dispatchAfterCommit(Long submissionId, Long examId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			throw new IllegalStateException("Exam grading dispatch requires a transaction");
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					dispatch(submissionId, examId);
				}
			}
		);
	}

	public void dispatch(Long submissionId, Long examId) {
		try {
			executor.execute(() -> workerProvider.getObject().grade(submissionId));
		} catch (TaskRejectedException exception) {
			log.atWarn()
				.addKeyValue("submissionId", submissionId)
				.addKeyValue("examId", examId)
				.log("Exam grading executor rejected submission");
		}
	}
}
