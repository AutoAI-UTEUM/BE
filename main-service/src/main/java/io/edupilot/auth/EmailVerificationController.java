package io.edupilot.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.edupilot.auth.dto.EmailVerificationConfirmRequest;
import io.edupilot.auth.dto.EmailVerificationResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth/email-verification")
@Tag(name = "Authentication")
public class EmailVerificationController {
	private final EmailVerificationService service;
	public EmailVerificationController(EmailVerificationService service) { this.service = service; }
	@PostMapping("/request")
	@Operation(summary = "내 계정 이메일 확인 안내 재요청", description = "로그인 필요. 이미 확인된 계정에는 새 링크를 발급하지 않습니다.")
	public ResponseEntity<ApiResponse<Void>> request(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
		service.request(user.userId(), ClientIpResolver.resolve(request));
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(null));
	}
	@GetMapping("/status")
	@Operation(summary = "내 계정 이메일 소유 확인 상태")
	public ResponseEntity<ApiResponse<EmailVerificationResponse>> status(@AuthenticationPrincipal AuthenticatedUser user) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.status(user.userId())));
	}
	@PostMapping("/confirm")
	@Operation(summary = "이메일 소유 확인 확정", description = "30분 유효·1회 사용. 링크 열기(GET)는 상태를 변경하지 않습니다. 이메일 확정은 연령·보호자 이용 승인과 별개이며, 신규 UNKNOWN/MANUAL_PENDING 계정은 업무 API·AI 이용이 계속 제한됩니다.")
	public ResponseEntity<ApiResponse<EmailVerificationResponse>> confirm(@Valid @RequestBody EmailVerificationConfirmRequest body,
		HttpServletRequest request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.confirm(body.token(), ClientIpResolver.resolve(request))));
	}
}
