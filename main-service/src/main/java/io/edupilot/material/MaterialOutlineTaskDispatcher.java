package io.edupilot.material;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class MaterialOutlineTaskDispatcher {

	private static final Logger log = LoggerFactory.getLogger(
		MaterialOutlineTaskDispatcher.class
	);

	private final Executor executor;
	private final MaterialOutlineGenerationService generationService;

	public MaterialOutlineTaskDispatcher(
		@Qualifier("materialExtractionExecutor") Executor executor,
		MaterialOutlineGenerationService generationService
	) {
		this.executor = executor;
		this.generationService = generationService;
	}

	public void submit(Long materialId) {
		try {
			executor.execute(() -> generationService.generate(materialId));
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Material outline scheduling deferred to backfill");
		}
	}
}
