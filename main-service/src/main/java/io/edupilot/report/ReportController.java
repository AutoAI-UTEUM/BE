package io.edupilot.report;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.report.dto.CreateReportRequest;
import io.edupilot.report.dto.ReportAcceptedResponse;
import io.edupilot.report.dto.ReportListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Reports")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {

	private final ReportApiService reportApiService;

	public ReportController(ReportApiService reportApiService) {
		this.reportApiService = reportApiService;
	}

	@PostMapping("/classrooms/{classroomId}/students/{studentId}/reports")
	@Operation(summary = "학생 리포트 생성 요청")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "202",
			description = "리포트 생성 접수 또는 기존 활성 요청 반환",
			useReturnTypeSchema = true
		)
	})
	public ResponseEntity<ApiResponse<ReportAcceptedResponse>> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@PathVariable Long studentId,
		@Valid @RequestBody CreateReportRequest request
	) {
		ReportAcceptedResponse response = reportApiService.create(
			user.userId(),
			user.role(),
			classroomId,
			studentId,
			request.toScope(),
			request.requestId()
		);
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.body(ApiResponse.success(response));
	}

	@GetMapping("/classrooms/{classroomId}/students/{studentId}/reports")
	@Operation(summary = "학생 리포트 버전 목록 조회")
	public ApiResponse<ReportListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@PathVariable Long studentId
	) {
		return ApiResponse.success(reportApiService.list(
			user.userId(), user.role(), classroomId, studentId
		));
	}

	@GetMapping("/reports/{reportId}")
	@Operation(summary = "리포트 생성 상태 또는 완료 상세 조회")
	public ApiResponse<Object> detail(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String reportId
	) {
		return ApiResponse.success(reportApiService.detail(
			user.userId(), user.role(), reportId
		));
	}
}
