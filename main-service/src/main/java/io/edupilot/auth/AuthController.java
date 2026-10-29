package io.edupilot.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthService.LoginResult;
import io.edupilot.auth.AuthService.RefreshResult;
import io.edupilot.auth.dto.AccessTokenResponse;
import io.edupilot.auth.dto.AuthSessionResponse;
import io.edupilot.auth.dto.EmailAvailabilityResponse;
import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.auth.dto.LoginRequest;
import io.edupilot.auth.dto.LoginResponse;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupResponse;
import io.edupilot.auth.validation.ValidEmail;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.global.response.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
@Validated
public class AuthController {

	private final AuthService authService;
	private final RefreshTokenCookie refreshTokenCookie;

	public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie) {
		this.authService = authService;
		this.refreshTokenCookie = refreshTokenCookie;
	}

	@PostMapping("/signup")
	@Operation(summary = "회원가입")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200", description = "계정 생성 성공. 로그인 토큰·세션은 발급하지 않습니다.", useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "400", description = "POLICY_CONSENT_REQUIRED: 준비된 필수 정책 버전의 동의 누락·중복·불일치 또는 요청 검증 오류.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "503", description = "SIGNUP_POLICY_NOT_READY: 가입 동의 필수 설정이지만 현재 유효한 동의 대상 정책이 없습니다.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	public ApiResponse<SignupResponse> signup(
		@Valid @RequestBody SignupRequest request,
		HttpServletRequest servletRequest
	) {
		return ApiResponse.success(authService.signup(
			request, ClientIpResolver.resolve(servletRequest),
			servletRequest.getHeader("User-Agent")
		));
	}

	@GetMapping("/email-availability")
	@Operation(summary = "회원가입 이메일 중복 확인")
	public ApiResponse<EmailAvailabilityResponse> emailAvailability(
		@ValidEmail @RequestParam String email
	) {
		return ApiResponse.success(authService.emailAvailability(email));
	}

	@PostMapping("/login")
	@Operation(summary = "로그인")
	public ResponseEntity<ApiResponse<LoginResponse>> login(
		@Valid @RequestBody LoginRequest request,
		HttpServletRequest servletRequest
	) {
		LoginResult result = authService.login(request, ClientIpResolver.resolve(servletRequest));
		return ResponseEntity.ok()
			.header(
				HttpHeaders.SET_COOKIE,
				refreshTokenCookie.create(
					result.refreshToken(),
					result.cookieMaxAge()
				).toString()
			)
			.body(ApiResponse.success(result.response()));
	}

	@PostMapping("/google")
	@Operation(
		summary = "Google 로그인 또는 가입",
		description = "같은 Google subject의 기존 로그인 또는 비충돌 신규 가입. "
			+ "이메일만 같은 기존 계정에는 자동 연결하지 않으며 충돌 시 토큰·쿠키를 발급하지 않습니다."
	)
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "200", description = "로그인 성공", useReturnTypeSchema = true
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "400", description = "POLICY_CONSENT_REQUIRED: 신규 가입에서 준비된 필수 정책 버전의 동의 오류 또는 요청 검증 오류.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "409",
			description = "EMAIL_ALREADY_EXISTS: 미연결 Google subject의 이메일이 기존 계정과 충돌. "
				+ "SIGNUP_REQUIRED: 비충돌 신규 가입에 역할 등 추가 정보 필요.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(
			responseCode = "503", description = "SIGNUP_POLICY_NOT_READY: 신규 가입의 필수 동의 대상 정책이 아직 준비되지 않았습니다. 기존 Google 로그인에는 적용하지 않습니다.",
			content = @Content(schema = @Schema(implementation = ErrorResponse.class))
		)
	})
	public ResponseEntity<ApiResponse<LoginResponse>> googleLogin(
		@Valid @RequestBody GoogleLoginRequest request,
		HttpServletRequest servletRequest
	) {
		LoginResult result = authService.googleLogin(
			request, ClientIpResolver.resolve(servletRequest),
			servletRequest.getHeader("User-Agent")
		);
		return ResponseEntity.ok()
			.header(
				HttpHeaders.SET_COOKIE,
				refreshTokenCookie.create(
					result.refreshToken(),
					result.cookieMaxAge()
				).toString()
			)
			.body(ApiResponse.success(result.response()));
	}

	@PostMapping("/refresh")
	@Operation(summary = "Access token 갱신")
	public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(
		@CookieValue(name = RefreshTokenCookie.NAME, required = false) String rawToken,
		HttpServletRequest servletRequest
	) {
		RefreshResult result = authService.refresh(rawToken, ClientIpResolver.resolve(servletRequest));
		return ResponseEntity.ok()
			.header(
				HttpHeaders.SET_COOKIE,
				refreshTokenCookie.create(
					result.refreshToken(),
					result.cookieMaxAge()
				).toString()
			)
			.body(ApiResponse.success(result.response()));
	}

	@PostMapping("/session/activity")
	@Operation(summary = "현재 인증 세션 활동 연장")
	public ApiResponse<AuthSessionResponse> recordActivity(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@CookieValue(name = RefreshTokenCookie.NAME, required = false) String rawToken
	) {
		return ApiResponse.success(
			authService.recordActivity(authenticatedUser.userId(), rawToken)
		);
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
