package io.edupilot.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.dto.PasswordResetConfirmRequest;
import io.edupilot.auth.dto.PasswordResetMessageResponse;
import io.edupilot.auth.dto.PasswordResetRequest;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/auth/password-reset")
@Tag(name = "Authentication")
public class PasswordResetController {

	private static final String REQUEST_MESSAGE = "등록된 이메일이면 재설정 안내를 발송했습니다.";
	private static final String CONFIRM_MESSAGE = "비밀번호가 변경되었습니다. 다시 로그인해주세요.";

	private final PasswordResetService service;

	public PasswordResetController(PasswordResetService service) {
		this.service = service;
	}

	@PostMapping("/request")
	@Operation(summary = "비밀번호 재설정 안내 요청")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202")
	public ResponseEntity<ApiResponse<PasswordResetMessageResponse>> request(
		@RequestBody(required = false) PasswordResetRequest request,
		HttpServletRequest servletRequest
	) {
		service.request(request == null ? null : request.email(), ClientIpResolver.resolve(servletRequest));
		return ResponseEntity.accepted().body(
			ApiResponse.success(new PasswordResetMessageResponse(REQUEST_MESSAGE))
		);
	}

	@PostMapping("/confirm")
	@Operation(summary = "비밀번호 재설정 확정")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200")
	public ApiResponse<PasswordResetMessageResponse> confirm(
		@RequestBody PasswordResetConfirmRequest request,
		HttpServletRequest servletRequest
	) {
		service.confirm(request.token(), request.newPassword(), ClientIpResolver.resolve(servletRequest));
		return ApiResponse.success(new PasswordResetMessageResponse(CONFIRM_MESSAGE));
	}

}
