package io.edupilot.admin.xai;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import io.edupilot.global.response.ApiResponse;
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

	private static final Logger log = LoggerFactory.getLogger(
		AdminXaiController.class
	);

	private final AdminXaiService service;
	private final AdminXaiUsageService usageService;
	private final XaiAlertConfigService alertConfigService;
	private final XaiSyncRateLimiter syncRateLimiter;
	private final Clock clock;

	public AdminXaiController(
		AdminXaiService service,
		AdminXaiUsageService usageService,
		XaiAlertConfigService alertConfigService,
		XaiSyncRateLimiter syncRateLimiter,
		Clock clock
	) {
		this.service = service;
		this.usageService = usageService;
		this.alertConfigService = alertConfigService;
		this.syncRateLimiter = syncRateLimiter;
		this.clock = clock;
	}

	@GetMapping("/credits")
	@Operation(summary = "관리자 xAI 크레딧 조회")
	public ApiResponse<AdminXaiCreditsResponse> credits(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_CREDITS_VIEWED", "/api/admin/xai/credits");
		return ApiResponse.success(service.credits());
	}

	@GetMapping("/status")
	@Operation(summary = "관리자 xAI 연동 상태 조회")
	public ApiResponse<AdminXaiStatusResponse> status(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_STATUS_VIEWED", "/api/admin/xai/status");
		return ApiResponse.success(service.status());
	}

	@GetMapping("/overview")
	@Operation(summary = "관리자 xAI 비용·잔액 요약 조회")
	public ApiResponse<AdminXaiOverviewResponse> overview(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_OVERVIEW_VIEWED", "/api/admin/xai/overview");
		return ApiResponse.success(service.overview());
	}

	@PostMapping("/sync")
	@Operation(summary = "관리자 xAI 비용·잔액 즉시 동기화")
	public ApiResponse<AdminXaiOverviewResponse> sync(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_SYNC", "/api/admin/xai/sync");
		syncRateLimiter.acquire(user.userId());
		return ApiResponse.success(service.sync());
	}

	@GetMapping("/usage")
	@Operation(summary = "관리자 xAI 내부 사용량 시계열 조회")
	public ApiResponse<AdminXaiUsageResponse> usage(
		@RequestParam LocalDate from,
		@RequestParam LocalDate to,
		@RequestParam(defaultValue = "DAY") XaiUsageGranularity granularity,
		@RequestParam(defaultValue = "COST") XaiUsageMetric metric,
		@RequestParam(defaultValue = "FEATURE") XaiUsageGroupBy groupBy,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_USAGE_VIEWED", "/api/admin/xai/usage");
		return ApiResponse.success(usageService.usage(
			from,
			to,
			granularity,
			metric,
			groupBy
		));
	}

	@GetMapping("/reconciliation")
	@Operation(summary = "관리자 내부 비용과 xAI 청구액 대조")
	public ApiResponse<AdminXaiReconciliationResponse> reconciliation(
		@RequestParam LocalDate from,
		@RequestParam LocalDate to,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(
			user.userId(),
			"XAI_RECONCILIATION_VIEWED",
			"/api/admin/xai/reconciliation"
		);
		return ApiResponse.success(service.reconciliation(from, to));
	}

	@GetMapping("/invoices")
	@Operation(summary = "관리자 xAI 월별 청구서 조회")
	public ApiResponse<AdminXaiInvoicesResponse> invoices(
		@RequestParam @Min(1) int year,
		@RequestParam @Min(1) @Max(12) int month,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_INVOICES_VIEWED", "/api/admin/xai/invoices");
		return ApiResponse.success(service.invoices(YearMonth.of(year, month)));
	}

	@GetMapping("/alerts")
	@Operation(summary = "관리자 xAI 위험 임계값 조회")
	public ApiResponse<AdminXaiAlertsResponse> alerts(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		audit(user.userId(), "XAI_ALERTS_VIEWED", "/api/admin/xai/alerts");
		return ApiResponse.success(alertConfigService.get());
	}

	@PutMapping("/alerts")
	@Operation(summary = "관리자 xAI 위험 임계값 수정")
	// 관리자 인프라 조회 전용 원칙의 예외: 운영 임계값을 안전한 API로 조정한다.
	public ApiResponse<AdminXaiAlertsResponse> updateAlerts(
		@Valid @RequestBody UpdateXaiAlertsRequest request,
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(alertConfigService.update(
			user.userId(),
			request
		));
	}

	private void audit(Long actorUserId, String action, String endpoint) {
		log.atInfo()
			.addKeyValue("actorUserId", actorUserId)
			.addKeyValue("action", action)
			.addKeyValue("endpoint", endpoint)
			.addKeyValue("occurredAt", clock.instant())
			.log("Admin xAI Management API accessed");
	}
}
