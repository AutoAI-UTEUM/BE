package io.edupilot.session;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.session.dto.CreateSessionRequest;
import io.edupilot.session.dto.MessageListResponse;
import io.edupilot.session.dto.PageMoveRequest;
import io.edupilot.session.dto.PageStateResponse;
import io.edupilot.session.dto.SessionCreateResponse;
import io.edupilot.session.dto.SessionDetailResponse;
import io.edupilot.session.dto.SessionListResponse;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/sessions")
@Validated
@Tag(name = "Sessions")
@SecurityRequirement(name = "bearerAuth")
public class SessionController {

	private final SessionService sessionService;
	private final SessionTurnService turnService;
	private final SessionMessageService messageService;

	public SessionController(
		SessionService sessionService,
		SessionTurnService turnService,
		SessionMessageService messageService
	) {
		this.sessionService = sessionService;
		this.turnService = turnService;
		this.messageService = messageService;
	}

	@PostMapping
	@Operation(summary = "학습 세션 생성 또는 기존 ACTIVE 세션 재사용")
	public ApiResponse<SessionCreateResponse> create(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody CreateSessionRequest request
	) {
		return ApiResponse.success(
			sessionService.create(authenticatedUser.userId(), request.materialId())
		);
	}

	@GetMapping
	@Operation(summary = "내 학습 세션 목록 조회")
	public ApiResponse<SessionListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
		@RequestParam(required = false) SessionStatus status
	) {
		return ApiResponse.success(
			sessionService.list(authenticatedUser.userId(), page, size, status)
		);
	}

	@GetMapping("/{sessionId}")
	@Operation(summary = "학습 세션 복원 상태 조회")
	public ApiResponse<SessionDetailResponse> detail(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId
	) {
		return ApiResponse.success(
			sessionService.detail(authenticatedUser.userId(), sessionId)
		);
	}

	@DeleteMapping("/{sessionId}")
	@Operation(summary = "학습 세션 논리 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId
	) {
		sessionService.delete(authenticatedUser.userId(), sessionId);
		return ApiResponse.success(null);
	}

	@PatchMapping("/{sessionId}/page")
	@Operation(summary = "LLM 호출 없는 현재 페이지 이동")
	public ApiResponse<PageStateResponse> movePage(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId,
		@Valid @RequestBody PageMoveRequest request
	) {
		return ApiResponse.success(sessionService.movePage(
			authenticatedUser.userId(),
			sessionId,
			request.pageNumber()
		));
	}

	@PostMapping("/{sessionId}/complete")
	@Operation(summary = "학습 세션 완료")
	public ApiResponse<SessionDetailResponse> complete(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId
	) {
		return ApiResponse.success(
			sessionService.complete(authenticatedUser.userId(), sessionId)
		);
	}

	@PostMapping("/{sessionId}/turns")
	@Operation(
		summary = "학습 turn 공통 경계 처리",
		description = """
			Epic 4에서는 AI를 호출하지 않고 고정 stub 메시지를 반환합니다.
			QUIZ_TYPE_SELECTED와 DIAGNOSIS_ANSWER_SUBMITTED도 형식만 검증하며,
			실제 퀴즈·진단 상태 검증과 처리는 Epic 6~7에서 연결합니다.
			"""
	)
	public ApiResponse<TurnResponse> turn(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId,
		@Valid @RequestBody TurnRequest request
	) {
		return ApiResponse.success(
			turnService.execute(authenticatedUser.userId(), sessionId, request)
		);
	}

	@GetMapping("/{sessionId}/messages")
	@Operation(summary = "세션 메시지 과거 방향 커서 조회")
	public ApiResponse<MessageListResponse> messages(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId,
		@RequestParam(required = false) String cursor,
		@RequestParam(defaultValue = "30") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(messageService.messages(
			authenticatedUser.userId(),
			sessionId,
			cursor,
			size
		));
	}
}
