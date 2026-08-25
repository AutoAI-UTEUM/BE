package io.edupilot.material;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.edupilot.ai.AiClient;
import io.edupilot.global.security.TraceIdFilter;

@Service
public class MaterialXaiFileLifecycleService {

	private static final Logger log = LoggerFactory.getLogger(
		MaterialXaiFileLifecycleService.class
	);

	private final AiClient aiClient;

	public MaterialXaiFileLifecycleService(AiClient aiClient) {
		this.aiClient = aiClient;
	}

	public void deleteAfterCommit(String fileId) {
		if (fileId == null || fileId.isBlank()) {
			return;
		}
		String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			deleteBestEffort(fileId, traceId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					deleteBestEffort(fileId, traceId);
				}
			}
		);
	}

	private void deleteBestEffort(String fileId, String traceId) {
		try {
			aiClient.deleteFile(fileId);
		} catch (RuntimeException ignored) {
			log.atWarn()
				.addKeyValue("traceId", traceId)
				.addKeyValue("fileId", fileId)
				.log("xAI file cleanup failed");
		}
	}
}
