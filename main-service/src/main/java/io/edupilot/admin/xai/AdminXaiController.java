package io.edupilot.admin.xai;

import java.time.LocalDate;
import java.time.YearMonth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.admin.xai.dto.AdminXaiAlertsResponse;
import io.edupilot.admin.xai.dto.AdminXaiCreditsResponse;
import io.edupilot.admin.xai.dto.AdminXaiInvoicesResponse;
import io.edupilot.admin.xai.dto.AdminXaiOverviewResponse;
import io.edupilot.admin.xai.dto.AdminXaiReconciliationResponse;
import io.edupilot.admin.xai.dto.AdminXaiStatusResponse;
import io.edupilot.admin.xai.dto.AdminXaiUsageResponse;
import io.edupilot.admin.xai.dto.UpdateXaiAlertsRequest;
import io.edupilot.admin.xai.dto.XaiUsageGranularity;
import io.edupilot.admin.xai.dto.XaiUsageGroupBy;
import io.edupilot.admin.xai.dto.XaiUsageMetric;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.admin.AdminAction;
import io.edupilot.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/admin/xai")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin xAI Management")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class AdminXaiController {

	private final AdminXaiService service;
	private final AdminXaiUsageService usageService;
	private final XaiAlertConfigService alertConfigService;
	private final XaiSyncRateLimiter syncRateLimiter;

	public AdminXaiController(
		AdminXaiService service,
		AdminXaiUsageService usageService,
		XaiAlertConfigService alertConfigService,
		XaiSyncRateLimiter syncRateLimiter
	) {
		this.service = service;
		this.usageService = usageService;
		this.alertConfigService = alertConfigService;
		this.syncRateLimiter = syncRateLimiter;
	}

	@GetMapping("/credits")
	@AdminAction("XAI_CREDITS_VIEWED")
	@Operation(summary = "관리자 xAI 크레딧 조회")
	public ApiResponse<AdminXaiCreditsResponse> credits(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(service.credits());
	}

	@GetMapping("/status")
	@AdminAction("XAI_STATUS_VIEWED")
	@Operation(summary = "관리자 xAI 연동 상태 조회")
	public ApiResponse<AdminXaiStatusResponse> status(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(service.status());
	}

	@GetMapping("/overview")
	@AdminAction("XAI_OVERVIEW_VIEWED")
	@Operation(summary = "관리자 xAI 비용·잔액 요약 조회")
	public ApiResponse<AdminXaiOverviewResponse> overview(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(service.overview());
	}

	@PostMapping("/sync")
	@AdminAction("XAI_SYNC")
	@Operation(summary = "관리자 xAI 비용·잔액 즉시 동기화")
	public ApiResponse<AdminXaiOverviewResponse> sync(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		syncRateLimiter.acquire(user.userId());
		return ApiResponse.success(service.sync());
	}

	@GetMapping("/usage")
	@AdminAction("XAI_USAGE_VIEWED")
	@Operation(summary = "관리자 xAI 내부 사용량 시계열 조회")
	public ApiResponse<AdminXaiUsageResponse> usage(
		@RequestParam LocalDate from,
		@RequestParam LocalDate to,
		@RequestParam(defaultValue = "DAY") XaiUsageGranularity granularity,
		@RequestParam(defaultValue = "COST") XaiUsageMetric metric,
		@RequestParam(defaultValue = "FEATURE") XaiUsageGroupBy groupBy,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(usageService.usage(
			from,
			to,
			granularity,
			metric,
			groupBy
		));
	}

	@GetMapping("/reconciliation")
	@AdminAction("XAI_RECONCILIATION_VIEWED")
	@Operation(summary = "관리자 내부 비용과 xAI 청구액 대조")
	public ApiResponse<AdminXaiReconciliationResponse> reconciliation(
		@RequestParam LocalDate from,
		@RequestParam LocalDate to,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(service.reconciliation(from, to));
	}

	@GetMapping("/invoices")
	@AdminAction("XAI_INVOICES_VIEWED")
	@Operation(summary = "관리자 xAI 월별 청구서 조회")
	public ApiResponse<AdminXaiInvoicesResponse> invoices(
		@RequestParam @Min(1) int year,
		@RequestParam @Min(1) @Max(12) int month,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(service.invoices(YearMonth.of(year, month)));
	}

	@GetMapping("/alerts")
	@AdminAction("XAI_ALERTS_VIEWED")
	@Operation(summary = "관리자 xAI 위험 임계값 조회")
	public ApiResponse<AdminXaiAlertsResponse> alerts(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(alertConfigService.get());
	}

	@PutMapping("/alerts")
	@AdminAction("XAI_ALERT_UPDATED")
	@Operation(summary = "관리자 xAI 위험 임계값 수정")
	// 관리자 인프라 조회 전용 원칙의 예외: 운영 임계값을 안전한 API로 조정한다.
	public ApiResponse<AdminXaiAlertsResponse> updateAlerts(
		@Valid @RequestBody UpdateXaiAlertsRequest request,
		@AuthenticationPrincipal AuthenticatedUser user,
		HttpServletRequest servletRequest
	) {
		XaiAlertConfigService.UpdateResult updated = alertConfigService.update(
			user.userId(), request
		);
		servletRequest.setAttribute("adminAuditBefore", updated.before());
		servletRequest.setAttribute("adminAuditAfter", updated.after());
		return ApiResponse.success(updated.response());
	}

}
