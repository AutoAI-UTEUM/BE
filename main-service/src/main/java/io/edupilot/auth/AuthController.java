package io.edupilot.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthService.LoginResult;
import io.edupilot.auth.AuthService.RefreshResult;
import io.edupilot.auth.dto.AccessTokenResponse;
import io.edupilot.auth.dto.LoginRequest;
import io.edupilot.auth.dto.LoginResponse;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

	private final AuthService authService;
	private final RefreshTokenCookie refreshTokenCookie;

	public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie) {
		this.authService = authService;
		this.refreshTokenCookie = refreshTokenCookie;
	}

	@PostMapping("/signup")
	@Operation(summary = "회원가입")
	public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.success(authService.signup(request));
	}

	@PostMapping("/login")
	@Operation(summary = "로그인")
	public ResponseEntity<ApiResponse<LoginResponse>> login(
		@Valid @RequestBody LoginRequest request
	) {
		LoginResult result = authService.login(request);
		return ResponseEntity.ok()
			.header(
				HttpHeaders.SET_COOKIE,
				refreshTokenCookie.create(result.refreshToken()).toString()
			)
			.body(ApiResponse.success(result.response()));
	}

	@PostMapping("/refresh")
	@Operation(summary = "Access token 갱신")
	public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(
		@CookieValue(name = RefreshTokenCookie.NAME, required = false) String rawToken
	) {
		RefreshResult result = authService.refresh(rawToken);
		return ResponseEntity.ok()
			.header(
				HttpHeaders.SET_COOKIE,
				refreshTokenCookie.create(result.refreshToken()).toString()
			)
			.body(ApiResponse.success(result.response()));
	}

	@PostMapping("/logout")
	@Operation(summary = "로그아웃")
	public ResponseEntity<ApiResponse<Void>> logout(
		@CookieValue(name = RefreshTokenCookie.NAME, required = false) String rawToken
	) {
		authService.logout(rawToken);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, refreshTokenCookie.expire().toString())
			.body(ApiResponse.success(null));
	}
}
