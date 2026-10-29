package io.edupilot.user.birthdate;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users/me/birthdate-correction-requests")
@Tag(name = "Users")
@SecurityRequirement(name = "bearerAuth")
public class BirthdateCorrectionController {
	private final BirthdateCorrectionService service;
	public BirthdateCorrectionController(BirthdateCorrectionService service) { this.service = service; }

	@PostMapping
	@Operation(summary = "본인 생년월일 관리자 수정 요청 접수", description = "이메일·보호자 대기 계정도 본인 요청을 접수할 수 있습니다. 같은 날짜 재요청은 멱등, 다른 날짜의 중복 대기 요청은 409. 접수는 User 생년월일이나 이용 자격을 변경하지 않습니다.")
	@ApiResponses({
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PENDING 접수 또는 동일 날짜의 기존 접수"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: 미래/누락/현재 DOB 동일. MALFORMED_REQUEST: 날짜 형식 오류"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증·현재 계정 상태 검증 실패"),
		@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "BIRTHDATE_CORRECTION_PENDING: 다른 날짜의 요청 대기 중")
	})
	public ResponseEntity<ApiResponse<BirthdateCorrectionDtos.Request>> submit(@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody BirthdateCorrectionDtos.Submit request) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.submit(user.userId(), request.requestedDateOfBirth())));
	}

	@GetMapping
	@Operation(summary = "본인 생년월일 수정 요청 상태 조회", description = "현재 로그인 사용자만 조회합니다. 접수 전 data는 null이며 다른 사용자의 요청 ID 경로는 제공하지 않습니다.")
	public ResponseEntity<ApiResponse<BirthdateCorrectionDtos.Request>> mine(@AuthenticationPrincipal AuthenticatedUser user) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.mine(user.userId())));
	}
}
