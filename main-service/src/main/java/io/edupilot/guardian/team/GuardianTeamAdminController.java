package io.edupilot.guardian.team;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import io.edupilot.admin.AdminAction;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/admin/guardian-requests")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Guardian Team Review")
@SecurityRequirement(name = "bearerAuth")
public class GuardianTeamAdminController {
	private final GuardianTeamService service;
	public GuardianTeamAdminController(GuardianTeamService service) { this.service = service; }
	@GetMapping
	@AdminAction("GUARDIAN_TEAM_LIST")
	@Operation(summary = "지정 담당자 보호자 확인 목록", description = "현재 DB ACTIVE ADMIN이며 별도 설정에 지정된 담당자만 조회합니다. 이 API가 관리자 권한을 부여하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.ListResponse>> list(@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return GuardianTeamSelfController.privateResponse(service.list(user.userId(), page, size));
	}
	@GetMapping("/{id}")
	@AdminAction("GUARDIAN_TEAM_DETAIL")
	@Operation(summary = "보호자 확인 검토 상세", description = "기한 내 최소 연락처·확인 수단·증거 참조와 감사 이벤트를 조회합니다. requiredScopes·optionalAiScope는 같은 status의 현재 고지에 따른 선택지이며 declaredScopes 및 serviceApproved·externalAiApproved와 별개입니다. stale이면 선택지는 빈 배열/null이고 기능 미준비는 503입니다. 회신 원문이나 신분증·주민등록번호를 수집하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Detail>> detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
		return GuardianTeamSelfController.privateResponse(service.detail(user.userId(), id));
	}
	@PostMapping("/{id}/confirmation")
	@AdminAction("GUARDIAN_TEAM_CONFIRMATION")
	@Operation(summary = "명시적 회신·전화 확인 수동 등록", description = "담당자가 신청번호·동의문·필수/선택 범위·명시적 응답을 확인하고 최소 증거 참조만 기록합니다. 메일 수신 자동 연결·본문 파싱 자동 승인은 없습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Status>> confirmation(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String id, @Valid @RequestBody GuardianTeamDtos.Confirmation body) {
		return GuardianTeamSelfController.privateResponse(service.confirm(user.userId(), id, body));
	}
	@PostMapping("/{id}/decision")
	@AdminAction("GUARDIAN_TEAM_DECISION")
	@Operation(summary = "보호자 팀 검토 승인·반려·보완 요청", description = "세대·revision 충돌을 거부하며 결정 트랜잭션에서 담당자 DB 권한을 다시 확인합니다. 승인에는 별도 명시적 응답과 관계·연락·증거·동의 범위 체크가 모두 필요합니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Status>> decision(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String id, @Valid @RequestBody GuardianTeamDtos.Decision body) {
		return GuardianTeamSelfController.privateResponse(service.decide(user.userId(), id, body));
	}
	@PostMapping("/{id}/revoke")
	@AdminAction("GUARDIAN_TEAM_REVOKE")
	@Operation(summary = "담당자 보호자 동의·승인 철회", description = "승인 세대번호를 변경하고 이용 자격을 즉시 해제합니다. 다시 승인되어도 이전 세대의 늦은 AI 결과는 재사용할 수 없습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Status>> revoke(@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable String id, @Valid @RequestBody GuardianTeamDtos.Revoke body) {
		return GuardianTeamSelfController.privateResponse(service.revoke(user.userId(), id, body));
	}
}
