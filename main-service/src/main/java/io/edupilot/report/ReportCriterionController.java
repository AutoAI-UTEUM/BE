package io.edupilot.report;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.report.dto.CreateReportCriterionRequest;
import io.edupilot.report.dto.ReportCriterionGenerationResponse;
import io.edupilot.report.dto.ReportCriterionListResponse;
import io.edupilot.report.dto.ReportCriterionResponse;
import io.edupilot.report.dto.UpdateReportCriterionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/classrooms/{classroomId}/report-criteria")
@Validated
@Tag(name = "Report criteria")
@SecurityRequirement(name = "bearerAuth")
public class ReportCriterionController {

	private final ReportCriterionService criterionService;
	private final ReportCriterionGenerationService generationService;

	public ReportCriterionController(
		ReportCriterionService criterionService,
		ReportCriterionGenerationService generationService
	) {
		this.criterionService = criterionService;
		this.generationService = generationService;
	}

	@PostMapping("/generate")
	@Operation(summary = "AI 평가 지표 자동 생성")
	public ResponseEntity<ApiResponse<ReportCriterionGenerationResponse>> generate(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId
	) {
		ReportCriterionGenerationResponse response = generationService.start(
			user.userId(), user.role(), classroomId
		);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.body(ApiResponse.success(response));
	}

	@GetMapping("/generation")
	@Operation(summary = "AI 평가 지표 생성 상태 조회")
	public ApiResponse<ReportCriterionGenerationResponse> generationStatus(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId
	) {
		return ApiResponse.success(generationService.status(
			user.userId(), user.role(), classroomId
		));
	}

	@GetMapping
	@Operation(summary = "강의실 리포트 평가 기준 목록 조회")
	public ApiResponse<ReportCriterionListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId
	) {
		return ApiResponse.success(criterionService.list(
			user.userId(), user.role(), classroomId
		));
	}

	@PostMapping
	@Operation(summary = "강의실 리포트 평가 기준 생성")
	public ApiResponse<ReportCriterionResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@Valid @RequestBody CreateReportCriterionRequest request
	) {
		return ApiResponse.success(criterionService.create(
			user.userId(), user.role(), classroomId, request
		));
	}

	@PatchMapping("/{criterionId}")
	@Operation(summary = "강의실 리포트 평가 기준 변경")
	public ApiResponse<ReportCriterionResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@PathVariable Long criterionId,
		@Valid @RequestBody UpdateReportCriterionRequest request
	) {
		return ApiResponse.success(criterionService.update(
			user.userId(), user.role(), classroomId, criterionId, request
		));
	}

	@DeleteMapping("/{criterionId}")
	@Operation(
		operationId = "deleteReportCriterion",
		summary = "강의실 커스텀 리포트 평가 기준 삭제",
		description = "최신 버전 ID로 해당 커스텀 기준의 전 버전을 물리 삭제합니다. "
			+ "진행 중 리포트 생성은 시작 시점에 동결한 기준 스냅샷을 사용하므로 영향을 받지 않습니다."
	)
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@PathVariable Long criterionId
	) {
		criterionService.delete(
			user.userId(), user.role(), classroomId, criterionId
		);
		return ApiResponse.success(null);
	}
}
