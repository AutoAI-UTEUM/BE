package io.edupilot.admin.xai;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.admin.xai.dto.AdminXaiCreditsResponse;
import io.edupilot.admin.xai.dto.AdminXaiOverviewResponse;
import io.edupilot.admin.xai.dto.AdminXaiStatusResponse;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/xai")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin xAI Management")
@SecurityRequirement(name = "bearerAuth")
public class AdminXaiController {

	private static final Logger log = LoggerFactory.getLogger(
		AdminXaiController.class
	);

	private final AdminXaiService service;
	private final XaiSyncRateLimiter syncRateLimiter;
	private final Clock clock;

	public AdminXaiController(
		AdminXaiService service,
		XaiSyncRateLimiter syncRateLimiter,
		Clock clock
	) {
		this.service = service;
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

	private void audit(Long actorUserId, String action, String endpoint) {
		log.atInfo()
			.addKeyValue("actorUserId", actorUserId)
			.addKeyValue("action", action)
			.addKeyValue("endpoint", endpoint)
			.addKeyValue("occurredAt", clock.instant())
			.log("Admin xAI Management API accessed");
	}
}
