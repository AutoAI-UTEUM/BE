package io.edupilot.report;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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

	public ReportCriterionController(ReportCriterionService criterionService) {
		this.criterionService = criterionService;
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
}
