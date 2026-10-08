package io.edupilot.guardian;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.ClientIpResolver;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth/guardian-verification")
@Tag(name = "Authentication")
public class GuardianWebController {
	private final GuardianWebService service;
	public GuardianWebController(GuardianWebService service) { this.service = service; }

	@PostMapping("/link")
	@Operation(summary = "보호자 동의 링크 발급", description = "로그인 필요. 기존 계정 예외에는 발급하지 않습니다. 기능·문자 업체·동의문 설정이 준비되지 않으면 503입니다.")
	public ResponseEntity<ApiResponse<GuardianWebDtos.Link>> link(@AuthenticationPrincipal AuthenticatedUser user,
		HttpServletRequest request) {
		return privateResponse(service.issue(user.userId(), ClientIpResolver.resolve(request)));
	}

	@PostMapping("/view")
	@Operation(summary = "보호자 동의 링크 조회", description = "토큰은 JSON 본문으로 전달합니다. 조회로 동의를 소비하지 않으며 미성년자 신원·전화번호는 반환하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianWebDtos.Status>> view(@RequestBody GuardianWebDtos.Token body,
		HttpServletRequest request) {
		return privateResponse(service.view(body, ClientIpResolver.resolve(request)));
	}

	@PostMapping("/consent")
	@Operation(summary = "보호자 동의 접수·문자 확인 요청", description = "발급 당시 동의문 버전, 동의와 법정대리인 자기신고가 필요합니다. 휴대전화 소유 확인은 법정대리인 관계 증명이 아닙니다.")
	public ResponseEntity<ApiResponse<GuardianWebDtos.Status>> consent(@RequestBody GuardianWebDtos.Consent body,
		HttpServletRequest request) {
		return privateResponse(service.consent(body, ClientIpResolver.resolve(request)));
	}

	@PostMapping("/verify")
	@Operation(summary = "보호자 휴대전화 확인", description = "코드는 저장하지 않습니다. PHONE_CONFIRMED는 휴대전화 확인 증거만 나타내며 이용 자격이나 연령·보호자 관계를 승인하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianWebDtos.Status>> verify(@RequestBody GuardianWebDtos.Code body,
		HttpServletRequest request) {
		return privateResponse(service.verify(body, ClientIpResolver.resolve(request)));
	}

	@PostMapping("/dispute")
	@Operation(summary = "보호자 동의 이의 접수", description = "링크 소지자의 이의를 REVIEW_REQUIRED로 기록합니다. 운영자 승인·예외 처리 권한 API는 정책 확정 전 제공하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianWebDtos.Status>> dispute(@RequestBody GuardianWebDtos.Token body,
		HttpServletRequest request) {
		return privateResponse(service.dispute(body, ClientIpResolver.resolve(request)));
	}

	private static <T> ResponseEntity<ApiResponse<T>> privateResponse(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
			.body(ApiResponse.success(body));
	}
}
