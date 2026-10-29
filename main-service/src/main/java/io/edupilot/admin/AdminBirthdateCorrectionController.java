package io.edupilot.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.user.birthdate.BirthdateCorrectionDtos;
import io.edupilot.user.birthdate.BirthdateCorrectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/admin/birthdate-correction-requests")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin Users")
@SecurityRequirement(name = "bearerAuth")
public class AdminBirthdateCorrectionController {
	private final BirthdateCorrectionService service;
	public AdminBirthdateCorrectionController(BirthdateCorrectionService service) { this.service = service; }

	@GetMapping
	@AdminAction("BIRTHDATE_CORRECTION_LIST")
	@Operation(summary = "관리자 생년월일 수정 요청 대기 목록", description = "요청 시각·ID 오름차순, page 0부터 size 1~100. 현재 DB ADMIN 권한 재검증과 본문 없는 감사 로그를 유지합니다. 승인·DOB 수정은 제공하지 않습니다.")
	public ResponseEntity<ApiResponse<BirthdateCorrectionDtos.ListResponse>> pending(@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.pending(user.userId(), page, size)));
	}

	@GetMapping("/{id}")
	@AdminAction("BIRTHDATE_CORRECTION_DETAIL")
	@Operation(summary = "관리자 생년월일 수정 요청 상세", description = "탈퇴 후에는 요청 DOB를 반환하지 않습니다. 수정 요청을 조회해도 이용 자격을 승인하지 않습니다.")
	public ResponseEntity<ApiResponse<BirthdateCorrectionDtos.Request>> detail(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long id) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.detail(user.userId(), id)));
	}
}
