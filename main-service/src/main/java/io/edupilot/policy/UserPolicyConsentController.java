package io.edupilot.policy;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.ClientIpResolver;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.policy.dto.PolicyConsentsRequest;
import io.edupilot.policy.dto.UserPolicyConsentsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/users/me/consents")
@Tag(name = "Policy Consents")
@SecurityRequirement(name = "bearerAuth")
public class UserPolicyConsentController {
	private final PolicyService policyService;

	public UserPolicyConsentController(PolicyService policyService) {
		this.policyService = policyService;
	}

	@GetMapping
	@Operation(summary = "내 정책 동의 이력과 미동의 현재 버전")
	public ApiResponse<UserPolicyConsentsResponse> mine(
		@AuthenticationPrincipal AuthenticatedUser user
	) {
		return ApiResponse.success(policyService.userConsents(user.userId()));
	}

	@PostMapping
	@Operation(summary = "현재 정책 버전 동의")
	public ApiResponse<UserPolicyConsentsResponse> agree(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestBody PolicyConsentsRequest request,
		HttpServletRequest servletRequest
	) {
		return ApiResponse.success(policyService.agree(
			user.userId(), request.consents(), ClientIpResolver.resolve(servletRequest),
			servletRequest.getHeader("User-Agent")
		));
	}
}
