package io.edupilot.material;

import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Component
public class MaterialOutlineTaskDispatcher {

	private static final Logger log = LoggerFactory.getLogger(
		MaterialOutlineTaskDispatcher.class
	);

	private final Executor executor;
	private final MaterialOutlineGenerationService generationService;
	private final MaterialOutlinePersistenceService persistenceService;

	public MaterialOutlineTaskDispatcher(
		@Qualifier("materialExtractionExecutor") Executor executor,
		MaterialOutlineGenerationService generationService,
		MaterialOutlinePersistenceService persistenceService
	) {
		this.executor = executor;
		this.generationService = generationService;
		this.persistenceService = persistenceService;
	}

	public void submit(Long materialId) {
		try {
			executor.execute(() -> generationService.generate(materialId));
		} catch (RuntimeException exception) {
			recordSchedulingFailure(materialId);
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Material outline scheduling deferred to backfill");
		}
	}

	public void submitManual(Long materialId) {
		try {
			executor.execute(() -> generationService.generateManual(materialId));
		} catch (RuntimeException exception) {
			recordSchedulingFailure(materialId);
			throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
		}
	}

	private void recordSchedulingFailure(Long materialId) {
		try {
			persistenceService.markFailed(materialId);
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", exception.getClass().getSimpleName())
				.log("Could not record material outline scheduling failure");
		}
	}
}
