package io.edupilot.global.config;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.global.response.ApiResponse;

@RestController
@RequestMapping("/api/health")
public class HealthController {

	@GetMapping
	ApiResponse<Map<String, String>> health() {
		return ApiResponse.success(Map.of("status", "UP"));
	}
}
