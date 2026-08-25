package io.edupilot.classroom;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.classroom.dto.CreateJoinRequest;
import io.edupilot.classroom.dto.JoinRequestListResponse;
import io.edupilot.classroom.dto.JoinRequestResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/classroom-join-requests")
@Validated
@Tag(name = "Classroom Join Requests")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomJoinRequestController {

	private final ClassroomService classroomService;

	public ClassroomJoinRequestController(ClassroomService classroomService) {
		this.classroomService = classroomService;
	}

	@PostMapping
	@Operation(summary = "초대 코드로 강의실 참여 요청")
	public ApiResponse<JoinRequestResponse> requestJoin(
		@AuthenticationPrincipal AuthenticatedUser user,
		@Valid @RequestBody CreateJoinRequest request
	) {
		return ApiResponse.success(classroomService.requestJoin(
			user.userId(), user.role(), request
		));
	}

	@GetMapping("/me")
	@Operation(summary = "내 강의실 참여 요청 목록 조회")
	public ApiResponse<JoinRequestListResponse> myJoinRequests(
		@AuthenticationPrincipal AuthenticatedUser user,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(classroomService.myJoinRequests(
			user.userId(), user.role(), page, size
		));
	}
}
