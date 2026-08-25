package io.edupilot.material;

import java.util.List;
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
import io.edupilot.material.MaterialExtractionPersistenceService.CompletionResult;
import io.edupilot.material.MaterialExtractionPersistenceService.ExtractionSnapshot;
import io.edupilot.material.storage.FileStorage;

@Service
public class MaterialExtractionService {

	private static final Logger log = LoggerFactory.getLogger(MaterialExtractionService.class);

	private final MaterialExtractionPersistenceService persistenceService;
	private final FileStorage fileStorage;
	private final AiClient aiClient;
	private final MaterialProperties properties;
	private final MaterialOutlineTaskDispatcher outlineTaskDispatcher;
	private final MaterialCaptionTaskDispatcher captionTaskDispatcher;
	private final MaterialXaiFileLifecycleService xaiFileLifecycleService;

	public MaterialExtractionService(
		MaterialExtractionPersistenceService persistenceService,
		FileStorage fileStorage,
		AiClient aiClient,
		MaterialProperties properties,
		MaterialOutlineTaskDispatcher outlineTaskDispatcher,
		MaterialCaptionTaskDispatcher captionTaskDispatcher,
		MaterialXaiFileLifecycleService xaiFileLifecycleService
	) {
		this.persistenceService = persistenceService;
		this.fileStorage = fileStorage;
		this.aiClient = aiClient;
		this.properties = properties;
		this.outlineTaskDispatcher = outlineTaskDispatcher;
		this.captionTaskDispatcher = captionTaskDispatcher;
		this.xaiFileLifecycleService = xaiFileLifecycleService;
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
			logWarnings(materialId, traceId, response.warnings());
			if (response.pageCount() > properties.maxPages()) {
				deleteUnretainedFile(response.xaiFileId());
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

			CompletionResult completion = persistenceService.complete(
				materialId,
				response.pages(),
				response.xaiFileId()
			);
			if (completion.applied()) {
				if (completion.replacedXaiFileId() != null) {
					xaiFileLifecycleService.deleteAfterCommit(
						completion.replacedXaiFileId()
					);
				}
				outlineTaskDispatcher.submit(materialId);
				captionTaskDispatcher.submit(materialId);
			} else {
				deleteUnretainedFile(response.xaiFileId());
				log.atInfo()
					.addKeyValue("materialId", materialId)
					.addKeyValue("reason", "STATE_CHANGED")
					.log("Material extraction result discarded");
			}
		} catch (RuntimeException exception) {
			persistenceService.fail(
				materialId,
				failureReason(exception),
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

	private void deleteUnretainedFile(String xaiFileId) {
		if (xaiFileId != null && !xaiFileId.isBlank()) {
			xaiFileLifecycleService.deleteAfterCommit(xaiFileId);
		}
	}

	private void logWarnings(
		Long materialId,
		String traceId,
		List<ExtractResponse.Warning> warnings
	) {
		for (ExtractResponse.Warning warning : warnings) {
			log.atWarn()
				.addKeyValue("traceId", traceId)
				.addKeyValue("materialId", materialId)
				.addKeyValue(
					"warningType",
					warning == null ? null : warning.type()
				)
				.log("Material extraction warning ignored");
		}
	}

	private MaterialFailureReason failureReason(RuntimeException exception) {
		if (!(exception instanceof AiClientException aiException)
			|| aiException.upstreamCode() == null) {
			return MaterialFailureReason.EXTRACTION_FAILED;
		}
		return switch (aiException.upstreamCode()) {
			case "UNSUPPORTED_FORMAT" -> MaterialFailureReason.UNSUPPORTED_FORMAT;
			case "ENCRYPTED_PDF" -> MaterialFailureReason.ENCRYPTED_PDF;
			case "NO_TEXT_CONTENT" -> MaterialFailureReason.NO_TEXT_CONTENT;
			case "FILE_TOO_LARGE" -> MaterialFailureReason.FILE_TOO_LARGE;
			case "PAGE_LIMIT_EXCEEDED" -> MaterialFailureReason.PAGE_LIMIT_EXCEEDED;
			default -> MaterialFailureReason.EXTRACTION_FAILED;
		};
	}

	private String safeReason(RuntimeException exception) {
		if (exception instanceof AiClientException aiException) {
			return aiException.errorCode().code();
		}
		return exception.getClass().getSimpleName();
	}
}
