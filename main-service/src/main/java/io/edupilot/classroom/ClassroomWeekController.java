package io.edupilot.classroom;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.classroom.dto.ClassroomWeekListResponse;
import io.edupilot.classroom.dto.ClassroomWeekResponse;
import io.edupilot.classroom.dto.CreateClassroomWeekRequest;
import io.edupilot.classroom.dto.ReorderClassroomWeeksRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekStatusRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekRequest;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/classrooms/{id}/weeks")
@Tag(name = "Classroom Weeks")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomWeekController {

	private final ClassroomWeekService weekService;

	public ClassroomWeekController(ClassroomWeekService weekService) {
		this.weekService = weekService;
	}

	@GetMapping
	@Operation(summary = "강의실 주차·자료 목록 조회")
	public ApiResponse<ClassroomWeekListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(weekService.list(
			user.userId(), user.role(), classroomId
		));
	}

	@PostMapping
	@Operation(summary = "강의실 주차 생성")
	public ApiResponse<ClassroomWeekResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@Valid @RequestBody CreateClassroomWeekRequest request
	) {
		return ApiResponse.success(weekService.create(
			user.userId(), user.role(), classroomId, request
		));
	}

	@PatchMapping("/{weekNumber}")
	@Operation(summary = "강의실 주차 수정")
	public ApiResponse<ClassroomWeekResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable int weekNumber,
		@RequestBody UpdateClassroomWeekRequest request
	) {
		return ApiResponse.success(weekService.update(
			user.userId(), user.role(), classroomId, weekNumber, request
		));
	}

	@DeleteMapping("/{weekNumber}")
	@Operation(summary = "강의실 주차 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable int weekNumber
	) {
		weekService.delete(user.userId(), user.role(), classroomId, weekNumber);
		return ApiResponse.success(null);
	}

	@PatchMapping("/{weekId}/status")
	@Operation(summary = "강의실 주차 상태 변경")
	public ApiResponse<ClassroomWeekResponse> changeStatus(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable Long weekId,
		@Valid @RequestBody UpdateClassroomWeekStatusRequest request
	) {
		return ApiResponse.success(weekService.changeStatus(
			user.userId(), user.role(), classroomId, weekId, request
		));
	}

	@PatchMapping("/reorder")
	@Operation(summary = "강의실 주차 표시 순서 변경")
	public ApiResponse<ClassroomWeekListResponse> reorder(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@Valid @RequestBody ReorderClassroomWeeksRequest request
	) {
		return ApiResponse.success(weekService.reorder(
			user.userId(), user.role(), classroomId, request
		));
	}

	@PostMapping("/{weekNumber}/materials/{materialId}")
	@Operation(summary = "강의실 주차에 기존 자료 연결")
	public ApiResponse<ClassroomWeekResponse> link(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable int weekNumber,
		@PathVariable Long materialId
	) {
		return ApiResponse.success(weekService.link(
			user.userId(), user.role(), classroomId, weekNumber, materialId
		));
	}

	@DeleteMapping("/{weekNumber}/materials/{materialId}")
	@Operation(summary = "강의실 주차 자료 연결 해제")
	public ApiResponse<ClassroomWeekResponse> unlink(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable int weekNumber,
		@PathVariable Long materialId
	) {
		return ApiResponse.success(weekService.unlink(
			user.userId(), user.role(), classroomId, weekNumber, materialId
		));
	}
}
