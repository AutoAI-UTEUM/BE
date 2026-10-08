package io.edupilot.guardian.team;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.edupilot.auth.ClientIpResolver;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth/guardian-team")
@Tag(name = "Guardian Team Review")
public class GuardianTeamPublicController {
	private final GuardianTeamService service;
	public GuardianTeamPublicController(GuardianTeamService service) { this.service = service; }
	@PostMapping("/view")
	@Operation(summary = "보호자 팀 확인 동의 안내·회신 양식", description = "토큰을 JSON 본문으로 받습니다. 조회만으로 소비·동의·승인하지 않습니다. 아동 이름·생년월일·보호자 연락처를 반환하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.View>> view(@Valid @RequestBody GuardianTeamDtos.Token body,
		HttpServletRequest request) {
		return GuardianTeamSelfController.privateResponse(service.publicView(body, ClientIpResolver.resolve(request)));
	}
	@PostMapping("/consent")
	@Operation(summary = "보호자 의사·자기신고 접수", description = "필수 서비스 동의와 선택 외부 AI 동의를 분리합니다. 웹 체크와 링크 클릭은 명시적 이메일 회신·전화 확인 또는 관계 심사를 대신하지 않으며 팀 승인 전 이용을 허용하지 않습니다.")
	public ResponseEntity<ApiResponse<GuardianTeamDtos.Status>> consent(@Valid @RequestBody GuardianTeamDtos.Consent body,
		HttpServletRequest request) {
		return GuardianTeamSelfController.privateResponse(service.consent(body, ClientIpResolver.resolve(request)));
	}
}
