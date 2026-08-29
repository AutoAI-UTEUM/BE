package io.edupilot.admin;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.admin.dto.AdminAiUsageSummaryResponse;
import io.edupilot.admin.dto.AdminAiUsageUserListResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/admin/ai-usage")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin AI Usage")
@SecurityRequirement(name = "bearerAuth")
public class AdminAiUsageController {

	private final AdminAiUsageService adminAiUsageService;

	public AdminAiUsageController(AdminAiUsageService adminAiUsageService) {
		this.adminAiUsageService = adminAiUsageService;
	}

	@GetMapping("/summary")
	@Operation(summary = "관리자 AI 사용량 요약 조회")
	public ApiResponse<AdminAiUsageSummaryResponse> summary(
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
	) {
		return ApiResponse.success(adminAiUsageService.summary(from, to));
	}

	@GetMapping("/users")
	@Operation(summary = "관리자 사용자별 AI 사용량 조회")
	public ApiResponse<AdminAiUsageUserListResponse> users(
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@RequestParam(required = false)
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer limit
	) {
		return ApiResponse.success(adminAiUsageService.users(from, to, limit));
	}
}
