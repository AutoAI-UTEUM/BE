package io.edupilot.material;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class MaterialXaiFileBackfillTaskDispatcher {

	private static final Logger log = LoggerFactory.getLogger(
		MaterialXaiFileBackfillTaskDispatcher.class
	);

	private final Executor executor;
	private final MaterialXaiFileBackfillService backfillService;

	public MaterialXaiFileBackfillTaskDispatcher(
		@Qualifier("materialXaiFileBackfillExecutor") Executor executor,
		MaterialXaiFileBackfillService backfillService
	) {
		this.executor = executor;
		this.backfillService = backfillService;
	}

	public void submit(Long materialId) {
		try {
			executor.execute(() -> backfillService.backfill(materialId));
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Material xAI file backfill scheduling deferred");
		}
	}
}
