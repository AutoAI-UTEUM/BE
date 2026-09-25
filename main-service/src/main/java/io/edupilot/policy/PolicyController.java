package io.edupilot.policy;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.global.response.ApiResponse;
import io.edupilot.policy.dto.PolicyDocumentResponse;
import io.edupilot.policy.dto.PolicyDocumentSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/policies")
@Tag(name = "Policies")
public class PolicyController {
	private final PolicyService policyService;

	public PolicyController(PolicyService policyService) {
		this.policyService = policyService;
	}

	@GetMapping("/current")
	@Operation(summary = "현재 유효한 이용약관·개인정보처리방침 버전")
	public ApiResponse<List<PolicyDocumentSummary>> current() {
		return ApiResponse.success(policyService.current());
	}

	@GetMapping("/{type}/{version}")
	@Operation(summary = "정책 버전 본문 조회")
	public ApiResponse<PolicyDocumentResponse> detail(
		@PathVariable PolicyType type, @PathVariable String version
	) {
		return ApiResponse.success(policyService.detail(type, version));
	}
}
