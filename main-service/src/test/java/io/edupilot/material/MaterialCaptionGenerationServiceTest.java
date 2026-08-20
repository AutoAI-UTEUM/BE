package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.CaptionsRequest;
import io.edupilot.ai.dto.CaptionsResponse;
import io.edupilot.material.MaterialCaptionPersistenceService.CaptionSnapshot;
import io.edupilot.material.MaterialCaptionPersistenceService.PageSnapshot;
import io.edupilot.material.PageImageRenderer.RenderedPage;

@ExtendWith(MockitoExtension.class)
class MaterialCaptionGenerationServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

	@Mock private MaterialCaptionPersistenceService persistenceService;
	@Mock private PageImageRenderer imageRenderer;
	@Mock private AiClient aiClient;

	private MaterialCaptionGenerationService service;

	@BeforeEach
	void setUp() {
		service = new MaterialCaptionGenerationService(
			persistenceService,
			imageRenderer,
			aiClient,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void splitsTwentyThreePagesAndKeepsGoingAfterChunkFailure() {
		List<PageSnapshot> pages = java.util.stream.IntStream.rangeClosed(1, 23)
			.mapToObj(number -> new PageSnapshot(number, "text-" + number))
			.toList();
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(
			new CaptionSnapshot("materials/00000000-0000-0000-0000-000000000001.pdf", pages)
		));
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			Consumer<RenderedPage> consumer = invocation.getArgument(2);
			for (int number = 1; number <= 23; number++) {
				consumer.accept(new RenderedPage(number, "key-" + number, new byte[]{1, 2}));
			}
			return null;
		}).when(imageRenderer).render(any(), any(), any());
		AtomicInteger call = new AtomicInteger();
		when(aiClient.captions(any())).thenAnswer(invocation -> {
			CaptionsRequest request = invocation.getArgument(0);
			if (call.getAndIncrement() == 0) {
				throw new IllegalStateException("chunk failed");
			}
			return response(request, request.pages().getFirst().pageNumber() == 11);
		});

		service.generate(10L);

		ArgumentCaptor<CaptionsRequest> requests = ArgumentCaptor.forClass(
			CaptionsRequest.class
		);
		verify(aiClient, org.mockito.Mockito.times(3)).captions(requests.capture());
		assertThat(requests.getAllValues()).extracting(value -> value.pages().size())
			.containsExactly(10, 10, 3);
		assertThat(requests.getAllValues().get(1).pages().getFirst().extractedText())
			.isEqualTo("text-11");
		verify(persistenceService).applyCaptions(eq(10L), eq(Map.ofEntries(
			Map.entry(11, "caption-11"), Map.entry(13, "caption-13"),
			Map.entry(14, "caption-14"), Map.entry(15, "caption-15"),
			Map.entry(16, "caption-16"), Map.entry(17, "caption-17"),
			Map.entry(18, "caption-18"), Map.entry(19, "caption-19"),
			Map.entry(20, "caption-20")
		)));
		verify(persistenceService).markCompleted(10L, NOW);
	}

	private CaptionsResponse response(CaptionsRequest request, boolean includeNull) {
		List<CaptionsResponse.PageCaption> captions = new ArrayList<>();
		for (CaptionsRequest.Page page : request.pages()) {
			captions.add(new CaptionsResponse.PageCaption(
				page.pageNumber(),
				includeNull && page.pageNumber() == 12
					? null
					: "caption-" + page.pageNumber()
			));
		}
		return new CaptionsResponse("1.0", captions, List.of());
	}
}
