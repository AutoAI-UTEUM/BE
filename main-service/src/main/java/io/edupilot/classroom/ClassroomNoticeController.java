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
import io.edupilot.classroom.dto.ClassroomNoticeListResponse;
import io.edupilot.classroom.dto.ClassroomNoticeResponse;
import io.edupilot.classroom.dto.CreateClassroomNoticeRequest;
import io.edupilot.classroom.dto.UpdateClassroomNoticeRequest;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/classrooms/{id}/notices")
@Validated
@Tag(name = "Classroom Notices")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomNoticeController {

	private final ClassroomNoticeService noticeService;

	public ClassroomNoticeController(ClassroomNoticeService noticeService) {
		this.noticeService = noticeService;
	}

	@GetMapping
	@Operation(summary = "강의실 공지 목록 조회")
	public ApiResponse<ClassroomNoticeListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(noticeService.list(
			user.userId(), user.role(), classroomId, page, size
		));
	}

	@PostMapping
	@Operation(summary = "강의실 공지 즉시 게시")
	public ApiResponse<ClassroomNoticeResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@Valid @RequestBody CreateClassroomNoticeRequest request
	) {
		return ApiResponse.success(noticeService.create(
			user.userId(), user.role(), classroomId, request
		));
	}

	@PatchMapping("/{noticeId}")
	@Operation(summary = "강의실 공지 수정")
	public ApiResponse<ClassroomNoticeResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable Long noticeId,
		@RequestBody UpdateClassroomNoticeRequest request
	) {
		return ApiResponse.success(noticeService.update(
			user.userId(), user.role(), classroomId, noticeId, request
		));
	}

	@DeleteMapping("/{noticeId}")
	@Operation(summary = "강의실 공지 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable("id") Long classroomId,
		@PathVariable Long noticeId
	) {
		noticeService.delete(user.userId(), user.role(), classroomId, noticeId);
		return ApiResponse.success(null);
	}
}
