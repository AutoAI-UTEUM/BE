package io.edupilot.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.AiUsage;
import io.edupilot.ai.dto.CriteriaSuggestRequest;
import io.edupilot.ai.dto.CriteriaSuggestResponse;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.aiusage.AiFeature;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialOverview;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.report.dto.CreateReportCriterionRequest;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class ReportCriterionGenerationServiceTest {

	@Mock private ClassroomService classroomService;
	@Mock private MaterialOverviewRepository overviewRepository;
	@Mock private ReportCriterionService criterionService;
	@Mock private AiClient aiClient;
	@Mock private AiUsageService aiUsageService;
	@Mock private MaterialOverview overview;

	private ReportCriterionGenerationService service;

	@BeforeEach
	void setUp() {
		service = service(Runnable::run);
	}

	@Test
	void registersThreeCriteriaAndStoresCompletedState() {
		readyOverview();
		when(criterionService.generationContext(30L)).thenReturn(
			new ReportCriterionService.GenerationContext(
				List.of("builtin_a", "inactive_custom"), 5
			)
		);
		when(aiClient.suggestCriteria(any())).thenReturn(response(
			3, "x".repeat(501), true
		));
		when(criterionService.registerGenerated(
			any(), any(), any(), any()
		)).thenReturn(3);

		var accepted = service.start(1L, UserRole.INSTRUCTOR, 30L);
		var completed = service.status(1L, UserRole.INSTRUCTOR, 30L);

		assertThat(accepted.status()).isEqualTo("RUNNING");
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.registeredCount()).isEqualTo(3);
		assertThat(completed.message()).contains("QUALITY_WARNING");

		ArgumentCaptor<CriteriaSuggestRequest> aiRequest =
			ArgumentCaptor.forClass(CriteriaSuggestRequest.class);
		verify(aiClient).suggestCriteria(aiRequest.capture());
		assertThat(aiRequest.getValue().existingCriterionKeys())
			.containsExactly("builtin_a", "inactive_custom");
		assertThat(aiRequest.getValue().materials()).singleElement()
			.satisfies(material -> {
				assertThat(material.title()).isEqualTo("자료 제목");
				assertThat(material.materialSummary()).isEqualTo("자료 요약");
				assertThat(material.sections()).hasSize(1);
			});

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CreateReportCriterionRequest>> registrations =
			ArgumentCaptor.forClass(List.class);
		verify(criterionService).registerGenerated(
			any(), any(), any(), registrations.capture()
		);
		assertThat(registrations.getValue()).hasSize(3);
		CreateReportCriterionRequest first = registrations.getValue().getFirst();
		assertThat(first.description()).hasSize(500);
		assertThat(first.rubric()).containsEntry("summary", "루브릭 0");
		assertThat(first.allowedSources())
			.containsExactly(ReportSourceType.SESSION);
		assertThat(first.weight()).isEqualByComparingTo("1.0");
		assertThat(first.minEvidence()).isEqualTo(2);
		verify(aiUsageService).record(
			1L,
			AiFeature.CRITERIA,
			new AiUsage("grok-criteria", 25L, 10L, null),
			true
		);
	}

	@Test
	void rejectsBeforeAiWhenNoReadyOverviewExists() {
		when(overviewRepository.findReadyByClassroomId(30L))
			.thenReturn(List.of());

		assertThatThrownBy(() -> service.start(
			1L, UserRole.INSTRUCTOR, 30L
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(
				ErrorCode.REPORT_CRITERIA_GENERATION_NOT_READY
			)
		);
		verify(aiClient, never()).suggestCriteria(any());
	}

	@Test
	void rejectsBeforeAiWhenOnlyTwoSlotsRemain() {
		readyOverview();
		when(criterionService.generationContext(30L)).thenReturn(
			new ReportCriterionService.GenerationContext(List.of(), 2)
		);

		assertThatThrownBy(() -> service.start(
			1L, UserRole.INSTRUCTOR, 30L
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(
				ErrorCode.REPORT_CRITERION_LIMIT_EXCEEDED
			)
		);
		verify(aiClient, never()).suggestCriteria(any());
	}

	@Test
	void rejectsEntireAiResultWhenItExceedsAvailableSlots() {
		readyOverview();
		when(criterionService.generationContext(30L)).thenReturn(
			new ReportCriterionService.GenerationContext(List.of(), 3)
		);
		when(aiClient.suggestCriteria(any())).thenReturn(response(4, "설명", false));

		service.start(1L, UserRole.INSTRUCTOR, 30L);

		assertThat(service.status(1L, UserRole.INSTRUCTOR, 30L).status())
			.isEqualTo("FAILED");
		assertThat(service.status(1L, UserRole.INSTRUCTOR, 30L).message())
			.contains("기존 지표 정리");
		verify(criterionService, never()).registerGenerated(
			any(), any(), any(), any()
		);
	}

	@Test
	void duplicateRegistrationRollsGenerationStateToFailed() {
		readyOverview();
		when(criterionService.generationContext(30L)).thenReturn(
			new ReportCriterionService.GenerationContext(List.of(), 5)
		);
		when(aiClient.suggestCriteria(any())).thenReturn(response(3, "설명", false));
		when(criterionService.registerGenerated(any(), any(), any(), any()))
			.thenThrow(new BusinessException(ErrorCode.REPORT_CRITERION_DUPLICATE));

		service.start(1L, UserRole.INSTRUCTOR, 30L);

		var failed = service.status(1L, UserRole.INSTRUCTOR, 30L);
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.message()).contains("중복");
	}

	@Test
	void rejectsSecondRequestWhileGenerationIsRunning() {
		service = service(command -> { });
		readyOverview();
		when(criterionService.generationContext(30L)).thenReturn(
			new ReportCriterionService.GenerationContext(List.of(), 5)
		);

		service.start(1L, UserRole.INSTRUCTOR, 30L);

		assertThatThrownBy(() -> service.start(
			1L, UserRole.INSTRUCTOR, 30L
		)).isInstanceOfSatisfying(BusinessException.class, exception -> {
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.REPORT_CRITERION_DUPLICATE);
			assertThat(exception.clientMessage()).contains("진행 중");
		});
	}

	@Test
	void returnsIdleBeforeAnyGenerationRequest() {
		var status = service.status(1L, UserRole.INSTRUCTOR, 30L);

		assertThat(status.status()).isEqualTo("IDLE");
		assertThat(status.registeredCount()).isNull();
		assertThat(status.message()).isNull();
	}

	@Test
	void aiFailureStoresFailedStateWithoutRegistration() {
		readyOverview();
		when(criterionService.generationContext(30L)).thenReturn(
			new ReportCriterionService.GenerationContext(List.of(), 5)
		);
		when(aiClient.suggestCriteria(any()))
			.thenThrow(new RuntimeException("upstream failure"));

		service.start(1L, UserRole.INSTRUCTOR, 30L);

		var failed = service.status(1L, UserRole.INSTRUCTOR, 30L);
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.message()).contains("다시 시도");
		verify(criterionService, never()).registerGenerated(
			any(), any(), any(), any()
		);
	}

	private ReportCriterionGenerationService service(Executor executor) {
		return new ReportCriterionGenerationService(
			classroomService,
			overviewRepository,
			criterionService,
			aiClient,
			aiUsageService,
			executor
		);
	}

	private void readyOverview() {
		OutlineResponse outline = new OutlineResponse(
			"1.0",
			"자료 요약",
			List.of(new OutlineResponse.Section(
				"도입", 1, 2, List.of("핵심")
			)),
			null,
			2,
			null
		);
		when(overviewRepository.findReadyByClassroomId(30L))
			.thenReturn(List.of(overview));
		when(overview.getOutline()).thenReturn(outline);
		lenient().when(overview.getMaterialTitle()).thenReturn("자료 제목");
	}

	private CriteriaSuggestResponse response(
		int count,
		String firstDescription,
		boolean warning
	) {
		List<CriteriaSuggestResponse.Criterion> criteria =
			java.util.stream.IntStream.range(0, count)
				.mapToObj(index -> new CriteriaSuggestResponse.Criterion(
					"generated_" + index,
					"생성 기준 " + index,
					index == 0 ? firstDescription : "설명 " + index,
					"루브릭 " + index,
					List.of(ReportSourceType.SESSION),
					BigDecimal.ONE,
					2
				))
				.toList();
		return new CriteriaSuggestResponse(
			"1.0",
			criteria,
			warning
				? List.of(new CriteriaSuggestResponse.Warning(
					"QUALITY_WARNING", "일부 개요가 짧습니다."
				))
				: List.of(),
			new AiUsage("grok-criteria", 25L, 10L, null)
		);
	}
}
