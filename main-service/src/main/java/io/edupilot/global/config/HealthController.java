package io.edupilot.global.config;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.global.response.ApiResponse;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	private final ReadinessService readinessService;

	public HealthController(ReadinessService readinessService) {
		this.readinessService = readinessService;
	}

	@GetMapping
	ApiResponse<Map<String, String>> health() {
		return ApiResponse.success(Map.of("status", "UP"));
	}

	@GetMapping("/ready")
	ResponseEntity<ReadinessResponse> ready() {
		ReadinessResponse response = readinessService.check();
		HttpStatus status = response.unavailable()
			? HttpStatus.SERVICE_UNAVAILABLE
			: HttpStatus.OK;
		return ResponseEntity.status(status).body(response);
	}
}
