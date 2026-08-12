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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.global.error.ErrorCode;
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

	private MaterialExtractionService extractionService;
	private ByteArrayResource resource;

	@BeforeEach
	void setUp() {
		extractionService = new MaterialExtractionService(
			persistenceService,
			fileStorage,
			aiClient,
			new MaterialProperties(45, 300, Duration.ofMinutes(30))
		);
		resource = new ByteArrayResource("%PDF-test".getBytes());
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(
			new ExtractionSnapshot(10L, "materials/key.pdf")
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
			.thenReturn(new ExtractResponse("1.0", 2, pages));
		when(persistenceService.complete(10L, pages)).thenReturn(true);

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).complete(10L, pages);
		verify(persistenceService, never()).fail(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void pageLimitMarksMaterialFailedWithoutSavingPages() {
		List<ExtractedPage> pages = java.util.stream.IntStream.rangeClosed(1, 301)
			.mapToObj(number -> new ExtractedPage(number, "page"))
			.toList();
		when(aiClient.extract(resource))
			.thenReturn(new ExtractResponse("1.0", 301, pages));

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).fail(
			10L,
			MaterialFailureReason.PAGE_LIMIT_EXCEEDED,
			"trace-1"
		);
		verify(persistenceService, never()).complete(10L, pages);
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
	}

	@Test
	void changedStateDiscardsSuccessfulResult() {
		List<ExtractedPage> pages = List.of(new ExtractedPage(1, "first"));
		when(aiClient.extract(resource))
			.thenReturn(new ExtractResponse("1.0", 1, pages));
		when(persistenceService.complete(10L, pages)).thenReturn(false);

		extractionService.extract(10L, "trace-1");

		verify(persistenceService).complete(10L, pages);
		assertThat(org.slf4j.MDC.get("traceId")).isNull();
	}
}
