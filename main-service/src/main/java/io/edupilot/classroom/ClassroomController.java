package io.edupilot.classroom;

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
import io.edupilot.classroom.dto.ClassroomDetailResponse;
import io.edupilot.classroom.dto.ClassroomJoinRequestListResponse;
import io.edupilot.classroom.dto.ClassroomListResponse;
import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.InviteCodeResponse;
import io.edupilot.classroom.dto.JoinRequestProcessResponse;
import io.edupilot.classroom.dto.PermanentDeleteClassroomRequest;
import io.edupilot.classroom.dto.UpdateClassroomRequest;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/classrooms")
@Validated
@Tag(name = "Classrooms")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomController {

	private final ClassroomService classroomService;

	public ClassroomController(ClassroomService classroomService) {
		this.classroomService = classroomService;
	}

	@PostMapping
	@Operation(summary = "강의실 개설")
	public ApiResponse<ClassroomDetailResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreateClassroomRequest request
	) {
		return ApiResponse.success(classroomService.create(
			user.userId(),
			user.role(),
			request
		));
	}

	@GetMapping
	@Operation(summary = "내 강의실 목록 조회")
	public ApiResponse<ClassroomListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(required = false) ClassroomStatus status,
		@RequestParam(required = false) String q,
		@RequestParam(defaultValue = "RECENT") ClassroomSort sort,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(classroomService.list(
			user.userId(), user.role(), status, q, sort, page, size
		));
	}

	@GetMapping("/{id}")
	@Operation(summary = "강의실 상세 조회")
	public ApiResponse<ClassroomDetailResponse> detail(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(classroomService.detail(
			user.userId(), user.role(), classroomId
		));
	}

	@PatchMapping("/{id}")
	@Operation(summary = "강의실 수정")
	public ApiResponse<ClassroomDetailResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@RequestBody UpdateClassroomRequest request
	) {
		return ApiResponse.success(classroomService.update(
			user.userId(), user.role(), classroomId, request
		));
	}

	@DeleteMapping("/{id}")
	@Operation(summary = "강의실 완료 전환")
	public ApiResponse<ClassroomDetailResponse> complete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(classroomService.complete(
			user.userId(), user.role(), classroomId
		));
	}

	@DeleteMapping("/{id}/permanent")
	@Operation(summary = "강의실 영구 삭제")
	public ApiResponse<Void> deletePermanently(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@RequestBody PermanentDeleteClassroomRequest request
	) {
		classroomService.deletePermanently(
			user.userId(), user.role(), classroomId, request
		);
		return ApiResponse.success(null);
	}

	@GetMapping("/{id}/invite-code")
	@Operation(summary = "강의실 초대 코드 조회")
	public ApiResponse<InviteCodeResponse> inviteCode(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(classroomService.inviteCode(
			user.userId(), user.role(), classroomId
		));
	}

	@PostMapping("/{id}/invite-code/regenerate")
	@Operation(summary = "강의실 초대 코드 재발급")
	public ApiResponse<InviteCodeResponse> regenerateInviteCode(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(classroomService.regenerateInviteCode(
			user.userId(), user.role(), classroomId
		));
	}

	@GetMapping("/{id}/join-requests")
	@Operation(summary = "강의실 참여 요청 목록 조회")
	public ApiResponse<ClassroomJoinRequestListResponse> joinRequests(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@RequestParam(defaultValue = "PENDING") ClassroomJoinRequestStatus status,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(classroomService.joinRequests(
			user.userId(), user.role(), classroomId, status, page, size
		));
	}

	@PostMapping("/{id}/join-requests/{requestId}/approve")
	@Operation(summary = "강의실 참여 요청 승인")
	public ApiResponse<JoinRequestProcessResponse> approve(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable Long requestId
	) {
		return ApiResponse.success(classroomService.approve(
			user.userId(), user.role(), classroomId, requestId
		));
	}

	@PostMapping("/{id}/join-requests/{requestId}/reject")
	@Operation(summary = "강의실 참여 요청 거절")
	public ApiResponse<JoinRequestProcessResponse> reject(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable Long requestId
	) {
		return ApiResponse.success(classroomService.reject(
			user.userId(), user.role(), classroomId, requestId
		));
	}
}
