package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
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
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialOutlinePersistenceService.OutlineSnapshot;

@ExtendWith(MockitoExtension.class)
class MaterialOutlineGenerationServiceTest {

	@Mock private MaterialOutlinePersistenceService persistenceService;
	@Mock private MaterialOutlineMarkdownRenderer renderer;
	@Mock private AiClient aiClient;
	@Mock private AiUsageService aiUsageService;

	private MaterialOutlineGenerationService generationService;

	@BeforeEach
	void setUp() {
		lenient().when(persistenceService.claimAutomaticGeneration(10L))
			.thenReturn(true);
		generationService = new MaterialOutlineGenerationService(
			persistenceService,
			renderer,
			aiClient,
			aiUsageService,
			new PageQuizPlanProperties(false, false),
			org.mockito.Mockito.mock(io.edupilot.auth.EmailVerificationGate.class)
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
		verify(aiUsageService).record(
			1L,
			AiFeature.OUTLINE,
			response.usage(),
			true
		);
	}

	@Test
	void disabledPlanFlagOmitsRequestFieldAndUnexpectedResponsePlan() {
		OutlineSnapshot snapshot = snapshot();
		OutlineResponse response = responseWithPlan();
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(snapshot));
		when(aiClient.outline(request(snapshot))).thenReturn(response);
		when(renderer.render(response.withoutPageQuizPlan()))
			.thenReturn("rendered markdown");

		generationService.generate(10L);

		ArgumentCaptor<OutlineRequest> sent = ArgumentCaptor.forClass(
			OutlineRequest.class);
		verify(aiClient).outline(sent.capture());
		assertThat(sent.getValue().includePageQuizPlan()).isNull();
		verify(persistenceService).markReady(10L, "rendered markdown",
			response.withoutPageQuizPlan());
	}

	@Test
	void enabledPlanWithBackfillDisabledStillRequestsAllPagesAndStoresDecision() {
		generationService = new MaterialOutlineGenerationService(
			persistenceService, renderer, aiClient, aiUsageService,
			new PageQuizPlanProperties(true, false),
			org.mockito.Mockito.mock(io.edupilot.auth.EmailVerificationGate.class)
		);
		OutlineSnapshot snapshot = snapshot();
		OutlineRequest request = new OutlineRequest("1.0", snapshot.xaiFileId(),
			2, snapshot.pages(), true);
		OutlineResponse response = responseWithPlan();
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(snapshot));
		when(aiClient.outline(request)).thenReturn(response);
		when(renderer.render(response)).thenReturn("rendered markdown");

		generationService.generate(10L);

		verify(aiClient).outline(request);
		verify(persistenceService).markReady(10L, "rendered markdown", response);
	}

	@Test
	void incompletePagesDoNotRequestPlanEvenWhenEnabled() {
		generationService = new MaterialOutlineGenerationService(
			persistenceService, renderer, aiClient, aiUsageService,
			new PageQuizPlanProperties(true, false),
			org.mockito.Mockito.mock(io.edupilot.auth.EmailVerificationGate.class)
		);
		OutlineSnapshot incomplete = new OutlineSnapshot(1L, 2,
			"file-outline-phase-five", List.of(new OutlineRequest.Page(1, "첫 페이지")));
		OutlineRequest request = request(incomplete);
		OutlineResponse response = response();
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(incomplete));
		when(aiClient.outline(request)).thenReturn(response);
		when(renderer.render(response)).thenReturn("rendered markdown");

		generationService.generate(10L);

		verify(aiClient).outline(request);
		verify(persistenceService).markReady(10L, "rendered markdown", response);
	}

	@Test
	void invalidPlanStillStoresReadyOverviewWithoutPlan() {
		generationService = new MaterialOutlineGenerationService(
			persistenceService, renderer, aiClient, aiUsageService,
			new PageQuizPlanProperties(true, false),
			org.mockito.Mockito.mock(io.edupilot.auth.EmailVerificationGate.class)
		);
		OutlineSnapshot snapshot = snapshot();
		OutlineRequest request = new OutlineRequest("1.0", snapshot.xaiFileId(),
			2, snapshot.pages(), true);
		OutlineResponse base = response();
		OutlineResponse invalidPlan = new OutlineResponse(
			base.schemaVersion(), base.materialSummary(), base.sections(),
			base.quizCheckpoints(), List.of(
				new OutlineResponse.PageQuizPlan(1, false, "표지")
			), 2, base.usage());
		when(persistenceService.snapshot(10L)).thenReturn(Optional.of(snapshot));
		when(aiClient.outline(request)).thenReturn(invalidPlan);
		when(renderer.render(invalidPlan)).thenReturn("rendered markdown");

		generationService.generate(10L);

		assertThat(invalidPlan.pageQuizPlan()).isNull();
		verify(persistenceService).markReady(10L, "rendered markdown", invalidPlan);
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
	void deniedAutomaticClaimSkipsAiCall() {
		when(persistenceService.claimAutomaticGeneration(10L)).thenReturn(false);

		generationService.generate(10L);

		verify(aiClient, never()).outline(org.mockito.ArgumentMatchers.any());
		verify(persistenceService, never()).snapshot(10L);
	}

	@Test
	void manualRegenerationBypassesAutomaticClaimAndCountsFailureOnce() {
		when(persistenceService.snapshotForManual(10L))
			.thenReturn(Optional.of(snapshot()));
		when(aiClient.outline(request(snapshot())))
			.thenThrow(new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT));

		generationService.generateManual(10L);

		verify(persistenceService, never()).claimAutomaticGeneration(10L);
		verify(persistenceService).markFailed(10L);
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
			null,
			2,
			null
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
			1L,
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
			null,
			2,
			new AiUsage("grok-outline", 30L, 12L, 4L)
		);
	}

	private OutlineResponse responseWithPlan() {
		OutlineResponse base = response();
		return new OutlineResponse(base.schemaVersion(), base.materialSummary(),
			base.sections(), base.quizCheckpoints(), List.of(
				new OutlineResponse.PageQuizPlan(1, false, "표지"),
				new OutlineResponse.PageQuizPlan(2, true, "핵심 개념")
			), base.totalPages(), base.usage());
	}
}
