package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

@ExtendWith(MockitoExtension.class)
class MaterialXaiFileLifecycleServiceTest {

	@Mock
	private AiClient aiClient;

	@AfterEach
	void tearDown() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		MDC.clear();
	}

	@Test
	void deletesOnlyAfterTransactionCommit() {
		MaterialXaiFileLifecycleService service = service();
		TransactionSynchronizationManager.initSynchronization();

		service.deleteAfterCommit("file-10");

		verify(aiClient, never()).deleteFile("file-10");
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(synchronization -> synchronization.afterCommit());
		verify(aiClient).deleteFile("file-10");
	}

	@Test
	void missingFileIdDoesNotCallAiService() {
		MaterialXaiFileLifecycleService service = service();

		service.deleteAfterCommit(null);
		service.deleteAfterCommit("   ");

		verify(aiClient, never()).deleteFile(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void upstreamAndTimeoutFailuresRemainBestEffortWithoutSensitiveLogs() {
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-delete");
		doThrow(new AiClientException(
			ErrorCode.AI_SERVICE_UNAVAILABLE,
			new IllegalStateException("secret-xai-key PDF original content")
		)).when(aiClient).deleteFile("file-502");
		doThrow(new AiClientException(
			ErrorCode.AI_SERVICE_TIMEOUT,
			new IllegalStateException("timeout PDF original content")
		)).when(aiClient).deleteFile("file-timeout");
		Logger logger = (Logger) LoggerFactory.getLogger(
			MaterialXaiFileLifecycleService.class
		);
		ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
			new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			assertThatCode(() -> {
				service().deleteAfterCommit("file-502");
				service().deleteAfterCommit("file-timeout");
			})
				.doesNotThrowAnyException();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list).hasSize(2).allSatisfy(event -> {
			assertThat(event.getFormattedMessage())
				.isEqualTo("xAI file cleanup failed")
				.doesNotContain(
					"secret-xai-key",
					"PDF original content"
				);
			assertThat(event.getThrowableProxy()).isNull();
			assertThat(event.getKeyValuePairs().toString())
				.contains("trace-delete")
				.doesNotContain(
					"secret-xai-key",
					"PDF original content"
				);
		});
		assertThat(appender.list)
			.extracting(event -> event.getKeyValuePairs().toString())
			.anyMatch(values -> values.contains("file-502"))
			.anyMatch(values -> values.contains("file-timeout"));
	}

	private MaterialXaiFileLifecycleService service() {
		return new MaterialXaiFileLifecycleService(aiClient);
	}
}
