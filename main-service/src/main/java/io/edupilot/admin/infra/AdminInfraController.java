package io.edupilot.admin.infra;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.admin.infra.dto.AdminAppMetricsResponse;
import io.edupilot.admin.infra.dto.AdminInfraCostResponse;
import io.edupilot.admin.infra.dto.AdminInfraMetricsResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/infra")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Infrastructure")
@SecurityRequirement(name = "bearerAuth")
public class AdminInfraController {

	private final AdminInfraMetricsService metricsService;
	private final AdminInfraCostService costService;
	private final AdminAppMetricsService appMetricsService;

	public AdminInfraController(
		AdminInfraMetricsService metricsService,
		AdminInfraCostService costService,
		AdminAppMetricsService appMetricsService
	) {
		this.metricsService = metricsService;
		this.costService = costService;
		this.appMetricsService = appMetricsService;
	}

	@GetMapping("/metrics")
	@Operation(summary = "관리자 EC2 CloudWatch 지표 조회")
	public ApiResponse<AdminInfraMetricsResponse> metrics(
		@RequestParam(defaultValue = "prod") String env,
		@RequestParam(defaultValue = "24h") String range
	) {
		return ApiResponse.success(metricsService.metrics(env, range));
	}

	@GetMapping("/cost")
	@Operation(summary = "관리자 AWS 비용 조회")
	public ApiResponse<AdminInfraCostResponse> cost() {
		return ApiResponse.success(costService.cost());
	}

	@GetMapping("/app")
	@Operation(summary = "관리자 애플리케이션 지표 조회")
	public ApiResponse<AdminAppMetricsResponse> app() {
		return ApiResponse.success(appMetricsService.metrics());
	}
}
