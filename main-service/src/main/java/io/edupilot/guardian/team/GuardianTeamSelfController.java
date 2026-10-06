package io.edupilot.guardian.team;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users/me/guardian-requests")
@Tag(name = "Guardian Team Review")
@SecurityRequirement(name = "bearerAuth")
public class GuardianTeamSelfController {
	private final GuardianTeamService service;
	public GuardianTeamSelfController(GuardianTeamService service) { this.service = service; }

	@PostMapping
	@Operation(summary = "보호자 팀 확인 신청", description = "보호자 이름·연락처는 선택 입력입니다. 입력하면 아동 제공 정보로 최초 수집일부터 최대 5일 이내 미확인 정리를 추적합니다. 신청은 이용 승인이 아닙니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.View>> intake(@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody GuardianTeamDtos.Intake body) {
		return privateResponse(service.intake(user.userId(), body));
	}
	@GetMapping
	@Operation(summary = "내 보호자 확인 신청 상태·양식", description = "본인 신청만 조회합니다. 보호자 연락처나 회신 원문을 반환하지 않으며 미정 설정에서는 활성화되지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.View>> self(@AuthenticationPrincipal AuthenticatedUser user) {
		return privateResponse(service.self(user.userId()));
	}
	@PostMapping("/{id}/link")
	@Operation(summary = "보호자 의사 접수 링크 발급", description = "만료·한 번 사용·해시 저장 토큰입니다. 재발급은 기존 토큰과 확인 결과를 무효화하며 최초 수집·신청 만료일을 연장하지 않습니다. 같은 발급 키 재시도에는 토큰을 재전달하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Link>> link(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String id, @Valid @RequestBody GuardianTeamDtos.Mutation body) {
		return privateResponse(service.issueLink(user.userId(), id, body));
	}
	@PostMapping("/{id}/withdraw")
	@Operation(summary = "내 보호자 확인 신청·동의 철회 요청", description = "본인이 이용 승인을 해제하는 안전 경로입니다. 보호자 연락처·토큰·연결된 로컬 메일 사본을 정리하며 재승인은 새 확인을 거쳐야 합니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Status>> withdraw(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String id, @Valid @RequestBody GuardianTeamDtos.Mutation body) {
		return privateResponse(service.withdrawRequest(user.userId(), id, body));
	}
	static <T> ResponseEntity<ApiResponse<T>> privateResponse(T value) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
			.body(ApiResponse.success(value));
	}
}
