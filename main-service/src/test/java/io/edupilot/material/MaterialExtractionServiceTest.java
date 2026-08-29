package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialExtractionPersistenceService.CompletionResult;
import io.edupilot.material.MaterialExtractionPersistenceService.ExtractionSnapshot;
import io.edupilot.material.storage.FileStorage;

@ExtendWith(MockitoExtension.class)
class MaterialExtractionServiceTest {

	@Mock
	private MaterialExtractionPersistenceService persistenceService;

	@Mock
	private FileStorage fileStorage;

	@Mock
	private AiClient aiClient;

	@Mock
	private AiUsageService aiUsageService;

	@Mock
	private MaterialOutlineTaskDispatcher outlineTaskDispatcher;

	@Mock
	private MaterialCaptionTaskDispatcher captionTaskDispatcher;

	@Mock
	private MaterialXaiFileLifecycleService xaiFileLifecycleService;

	private MaterialExtractionService extractionService;
	private ByteArrayResource resource;

	@BeforeEach
	void setUp() {
		extractionService = new MaterialExtractionService(
			persistenceService,
			fileStorage,
			aiClient,
			aiUsageService,
			new MaterialProperties(45, 300, Duration.ofMinutes(30)),
			outlineTaskDispatcher,
			captionTaskDispatcher,
			xaiFileLifecycleService
		);
		resource = new ByteArrayResource("%PDF-test".getBytes());
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(
			new ExtractionSnapshot(10L, 1L, "materials/key.pdf")
		));
		when(fileStorage.load("materials/key.pdf")).thenReturn(resource);
	}

	@Test
	void successfulExtractionAppliesPages() {
		List<ExtractedPage> pages = List.of(
			new ExtractedPage(1, "first"),
			new ExtractedPage(2, "second")
		);
		when(aiClient.extract(resource))
			.thenReturn(new ExtractResponse(
				"1.0",
				2,
				pages,
				"file-new",
				List.of()
			));
		when(persistenceService.complete(10L, pages, "file-new"))
			.thenReturn(new CompletionResult(true, null));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).complete(10L, pages, "file-new");
		verify(outlineTaskDispatcher).submit(10L);
		verify(captionTaskDispatcher).submit(10L);
		verify(aiUsageService).record(
			1L,
			AiFeature.EXTRACT,
			null,
			true
		);
		verify(persistenceService, never()).fail(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void missingXaiFileIdKeepsSuccessfulReadyFlow() {
		List<ExtractedPage> pages = List.of(new ExtractedPage(1, "first"));
		when(aiClient.extract(resource))
			.thenReturn(new ExtractResponse("1.0", 1, pages));
		when(persistenceService.complete(10L, pages, null))
			.thenReturn(new CompletionResult(true, null));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).complete(10L, pages, null);
		verify(outlineTaskDispatcher).submit(10L);
		verify(captionTaskDispatcher).submit(10L);
		verify(xaiFileLifecycleService, never()).deleteAfterCommit(
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void warningsDoNotBlockReadyAndDoNotLogRawMessages() {
		List<ExtractedPage> pages = List.of(new ExtractedPage(1, "first"));
		when(aiClient.extract(resource)).thenReturn(new ExtractResponse(
			"1.0",
			1,
			pages,
			"file-new",
			List.of(
				new ExtractResponse.Warning(
					"FILE_UPLOAD_FAILED",
					"secret-xai-key PDF original content"
				),
				new ExtractResponse.Warning(
					"FUTURE_WARNING",
					"future PDF content"
				)
			)
		));
		when(persistenceService.complete(10L, pages, "file-new"))
			.thenReturn(new CompletionResult(true, null));
		Logger logger = (Logger) LoggerFactory.getLogger(
			MaterialExtractionService.class
		);
		ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
			new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			extractionService.extract(10L, "trace-warning");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		verify(persistenceService).complete(10L, pages, "file-new");
		assertThat(appender.list).hasSize(2);
		assertThat(appender.list)
			.extracting(event -> event.getKeyValuePairs().toString())
			.allMatch(values -> values.contains("trace-warning"))
			.anyMatch(values -> values.contains("FILE_UPLOAD_FAILED"))
			.anyMatch(values -> values.contains("FUTURE_WARNING"))
			.noneMatch(values -> values.contains("secret-xai-key"))
			.noneMatch(values -> values.contains("PDF original content"));
		assertThat(appender.list)
			.extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
			.noneMatch(message -> message.contains("secret-xai-key"))
			.noneMatch(message -> message.contains("PDF original content"));
	}

	@Test
	void replacedXaiFileIsDeletedThroughLifecycleHook() {
		List<ExtractedPage> pages = List.of(new ExtractedPage(1, "first"));
		when(aiClient.extract(resource)).thenReturn(new ExtractResponse(
			"1.0", 1, pages, "file-new", List.of()
		));
		when(persistenceService.complete(10L, pages, "file-new"))
			.thenReturn(new CompletionResult(true, "file-old"));

		extractionService.extract(10L, "trace-1");

		verify(xaiFileLifecycleService).deleteAfterCommit("file-old");
	}

	@Test
	void pageLimitMarksMaterialFailedWithoutSavingPages() {
		List<ExtractedPage> pages = java.util.stream.IntStream.rangeClosed(1, 301)
			.mapToObj(number -> new ExtractedPage(number, "page"))
			.toList();
		when(aiClient.extract(resource))
			.thenReturn(new ExtractResponse(
				"1.0", 301, pages, "file-rejected", List.of()
			));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).fail(
			10L,
			MaterialFailureReason.PAGE_LIMIT_EXCEEDED,
			"trace-1"
		);
		verify(persistenceService, never()).complete(
			10L,
			pages,
			"file-rejected"
		);
		verify(xaiFileLifecycleService).deleteAfterCommit("file-rejected");
	}

	@Test
	void aiFailureMarksMaterialFailed() {
		when(aiClient.extract(resource)).thenThrow(
			new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT)
		);

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).fail(
			10L,
			MaterialFailureReason.EXTRACTION_FAILED,
			"trace-1"
		);
		verify(aiUsageService).record(
			1L,
			AiFeature.EXTRACT,
			null,
			false
		);
	}

	@ParameterizedTest
	@CsvSource({
		"UNSUPPORTED_FORMAT, UNSUPPORTED_FORMAT",
		"ENCRYPTED_PDF, ENCRYPTED_PDF",
		"NO_TEXT_CONTENT, NO_TEXT_CONTENT",
		"FILE_TOO_LARGE, FILE_TOO_LARGE",
		"PAGE_LIMIT_EXCEEDED, PAGE_LIMIT_EXCEEDED"
	})
	void mapsKnownAiFailureCode(
		String upstreamCode,
		MaterialFailureReason expectedReason
	) {
		when(aiClient.extract(resource)).thenThrow(new AiClientException(
			ErrorCode.AI_RESPONSE_INVALID,
			AiFailureCategory.POLICY,
			false,
			upstreamCode,
			null
		));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).fail(10L, expectedReason, "trace-1");
	}

	@Test
	void unknownAiFailureCodeFallsBackToExtractionFailed() {
		when(aiClient.extract(resource)).thenThrow(new AiClientException(
			ErrorCode.AI_RESPONSE_INVALID,
			AiFailureCategory.POLICY,
			false,
			"UNKNOWN_EXTRACTION_ERROR",
			null
		));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).fail(
			10L,
			MaterialFailureReason.EXTRACTION_FAILED,
			"trace-1"
		);
	}

	@Test
	void nonAiFailureFallsBackToExtractionFailed() {
		when(aiClient.extract(resource)).thenThrow(new IllegalStateException("failed"));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).fail(
			10L,
			MaterialFailureReason.EXTRACTION_FAILED,
			"trace-1"
		);
	}

	@Test
	void changedStateDiscardsSuccessfulResult() {
		List<ExtractedPage> pages = List.of(new ExtractedPage(1, "first"));
		when(aiClient.extract(resource))
			.thenReturn(new ExtractResponse(
				"1.0", 1, pages, "file-discarded", List.of()
			));
		when(persistenceService.complete(10L, pages, "file-discarded"))
			.thenReturn(new CompletionResult(false, null));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).complete(10L, pages, "file-discarded");
		verify(xaiFileLifecycleService).deleteAfterCommit("file-discarded");
		verify(outlineTaskDispatcher, never()).submit(10L);
		assertThat(org.slf4j.MDC.get("traceId")).isNull();
	}
}
