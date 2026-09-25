package io.edupilot.admin;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.policy.PolicyService;
import io.edupilot.policy.PolicyType;
import io.edupilot.policy.dto.PolicyConsentStats;
import io.edupilot.policy.dto.PolicyDocumentResponse;
import io.edupilot.policy.dto.PolicyDocumentSummary;
import io.edupilot.policy.dto.PublishPolicyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/policies")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Policies")
@SecurityRequirement(name = "bearerAuth")
public class AdminPolicyController {
	private final PolicyService policyService;

	public AdminPolicyController(PolicyService policyService) {
		this.policyService = policyService;
	}

	/** 조회 전용 원칙의 예외: 승인된 새 문구를 불변 버전으로 감사 가능하게 등록한다. */
	@PostMapping
	@AdminAction("POLICY_PUBLISHED")
	@Operation(summary = "새 정책 버전 등록")
	public ApiResponse<PolicyDocumentResponse> publish(
		@AuthenticationPrincipal AuthenticatedUser actor,
		@Valid @RequestBody PublishPolicyRequest request
	) {
		return ApiResponse.success(policyService.publish(actor.userId(), request));
	}

	@GetMapping
	@Operation(summary = "정책 전 버전 목록")
	public ApiResponse<List<PolicyDocumentSummary>> all(
		@RequestParam(required = false) PolicyType type
	) {
		return ApiResponse.success(policyService.all(type));
	}

	@GetMapping("/consent-stats")
	@Operation(summary = "현재 정책 버전별 활성 회원 동의율")
	public ApiResponse<List<PolicyConsentStats>> stats() {
		return ApiResponse.success(policyService.stats());
	}
}
