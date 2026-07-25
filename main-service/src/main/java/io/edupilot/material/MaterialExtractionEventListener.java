package io.edupilot.material;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MaterialExtractionEventListener {

	private static final Logger log =
		LoggerFactory.getLogger(MaterialExtractionEventListener.class);

	private final Executor executor;
	private final MaterialExtractionService extractionService;
	private final MaterialExtractionPersistenceService persistenceService;

	public MaterialExtractionEventListener(
		@Qualifier("materialExtractionExecutor") Executor executor,
		MaterialExtractionService extractionService,
		MaterialExtractionPersistenceService persistenceService
	) {
		this.executor = executor;
		this.extractionService = extractionService;
		this.persistenceService = persistenceService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onExtractionRequested(MaterialExtractionRequested event) {
		try {
			executor.execute(() ->
				extractionService.extract(event.materialId(), event.traceId())
			);
		} catch (TaskRejectedException exception) {
			persistenceService.fail(event.materialId());
			log.warn(
				"Material extraction scheduling failed: materialId={}, reason=EXECUTOR_REJECTED",
				event.materialId()
			);
		}
	}
}
