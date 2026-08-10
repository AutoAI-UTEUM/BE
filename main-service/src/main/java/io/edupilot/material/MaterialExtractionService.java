package io.edupilot.material;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.MaterialExtractionPersistenceService.ExtractionSnapshot;
import io.edupilot.material.storage.FileStorage;

@Service
public class MaterialExtractionService {

	private static final Logger log = LoggerFactory.getLogger(MaterialExtractionService.class);

	private final MaterialExtractionPersistenceService persistenceService;
	private final FileStorage fileStorage;
	private final AiClient aiClient;
	private final MaterialProperties properties;

	public MaterialExtractionService(
		MaterialExtractionPersistenceService persistenceService,
		FileStorage fileStorage,
		AiClient aiClient,
		MaterialProperties properties
	) {
		this.persistenceService = persistenceService;
		this.fileStorage = fileStorage;
		this.aiClient = aiClient;
		this.properties = properties;
	}

	public void extract(Long materialId, String traceId) {
		Map<String, String> previousContext = MDC.getCopyOfContextMap();
		if (traceId != null && !traceId.isBlank()) {
			MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, traceId);
		}

		try {
			Optional<ExtractionSnapshot> snapshot =
				persistenceService.snapshot(materialId);
			if (snapshot.isEmpty()) {
				return;
			}

			ExtractResponse response = aiClient.extract(
				fileStorage.load(snapshot.get().storageKey())
			);
			if (response.pageCount() > properties.maxPages()) {
				persistenceService.fail(
					materialId,
					MaterialFailureReason.PAGE_LIMIT_EXCEEDED,
					traceId
				);
				log.atWarn()
					.addKeyValue("materialId", materialId)
					.addKeyValue("reason", "PAGE_LIMIT")
					.log("Material extraction rejected");
				return;
			}

			boolean applied = persistenceService.complete(materialId, response.pages());
			if (!applied) {
				log.atInfo()
					.addKeyValue("materialId", materialId)
					.addKeyValue("reason", "STATE_CHANGED")
					.log("Material extraction result discarded");
			}
		} catch (RuntimeException exception) {
			persistenceService.fail(
				materialId,
				MaterialFailureReason.EXTRACTION_FAILED,
				traceId
			);
			log.atWarn()
				.addKeyValue("materialId", materialId)
				.addKeyValue("reason", safeReason(exception))
				.log("Material extraction failed");
		} finally {
			if (previousContext == null) {
				MDC.clear();
			} else {
				MDC.setContextMap(previousContext);
			}
		}
	}

	private String safeReason(RuntimeException exception) {
		if (exception instanceof AiClientException aiException) {
			return aiException.errorCode().code();
		}
		return exception.getClass().getSimpleName();
	}
}
