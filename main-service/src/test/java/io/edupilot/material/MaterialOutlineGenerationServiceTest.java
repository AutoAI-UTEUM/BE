package io.edupilot.material;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialOutlinePersistenceService.OutlineSnapshot;

@ExtendWith(MockitoExtension.class)
class MaterialOutlineGenerationServiceTest {

	@Mock private MaterialOutlinePersistenceService persistenceService;
	@Mock private MaterialOutlineMarkdownRenderer renderer;
	@Mock private AiClient aiClient;

	private MaterialOutlineGenerationService generationService;

	@BeforeEach
	void setUp() {
		generationService = new MaterialOutlineGenerationService(
			persistenceService,
			renderer,
			aiClient
		);
	}

	@Test
	void successfulOutlineStoresMarkdownAndStructuredResponse() {
		OutlineSnapshot snapshot = snapshot();
		OutlineRequest request = request(snapshot);
		OutlineResponse response = response();
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(snapshot));
		when(aiClient.outline(request)).thenReturn(response);
		when(renderer.render(response)).thenReturn("rendered markdown");

		generationService.generate(10L);

		verify(persistenceService).markReady(
			10L,
			"rendered markdown",
			response
		);
		verify(persistenceService, never()).markFailed(10L);
	}

	@ParameterizedTest
	@MethodSource("outlineFailures")
	void outlineFailureMarksOnlyOverviewFailed(RuntimeException failure) {
		OutlineSnapshot snapshot = snapshot();
		OutlineRequest request = request(snapshot);
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(snapshot));
		when(aiClient.outline(request)).thenThrow(failure);

		generationService.generate(10L);

		verify(persistenceService).markFailed(10L);
		verify(persistenceService, never()).markReady(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	@Test
	void terminalOrUnavailableMaterialSkipsAiCall() {
		when(persistenceService.snapshot(10L)).thenReturn(Optional.empty());

		generationService.generate(10L);

		verify(aiClient, never()).outline(org.mockito.ArgumentMatchers.any());
		verify(persistenceService, never()).markFailed(10L);
	}

	@Test
	void nonContinuousOutlineMarksOverviewFailed() {
		OutlineSnapshot snapshot = snapshot();
		OutlineRequest request = request(snapshot);
		OutlineResponse invalid = new OutlineResponse(
			"1.0",
			"자료 요약입니다.",
			List.of(new OutlineResponse.Section(
				"누락된 첫 페이지",
				2,
				2,
				List.of("핵심")
			)),
			2
		);
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(snapshot));
		when(aiClient.outline(request)).thenReturn(invalid);

		generationService.generate(10L);

		verify(persistenceService).markFailed(10L);
		verify(persistenceService, never()).markReady(
			org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any()
		);
	}

	private static List<RuntimeException> outlineFailures() {
		return List.of(
			new AiClientException(ErrorCode.AI_RESPONSE_INVALID),
			new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT)
		);
	}

	private OutlineSnapshot snapshot() {
		return new OutlineSnapshot(
			2,
			"file-outline-phase-five",
			List.of(
				new OutlineRequest.Page(1, "첫 페이지 전체 텍스트"),
				new OutlineRequest.Page(2, "둘째 페이지 전체 텍스트")
			)
		);
	}

	private OutlineRequest request(OutlineSnapshot snapshot) {
		return new OutlineRequest(
			"1.0",
			snapshot.xaiFileId(),
			snapshot.totalPages(),
			snapshot.pages()
		);
	}

	private OutlineResponse response() {
		return new OutlineResponse(
			"1.0",
			"자료 요약입니다.",
			List.of(new OutlineResponse.Section(
				"전체 단원",
				1,
				2,
				List.of("핵심")
			)),
			2
		);
	}
}
