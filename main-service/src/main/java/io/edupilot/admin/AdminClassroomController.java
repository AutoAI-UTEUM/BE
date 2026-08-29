package io.edupilot.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.admin.dto.AdminClassroomDetailResponse;
import io.edupilot.admin.dto.AdminClassroomListResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/admin/classrooms")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@Tag(name = "Admin Classrooms")
@SecurityRequirement(name = "bearerAuth")
public class AdminClassroomController {

	private final AdminClassroomService adminClassroomService;

	public AdminClassroomController(AdminClassroomService adminClassroomService) {
		this.adminClassroomService = adminClassroomService;
	}

	@GetMapping
	@Operation(summary = "관리자 강의실 목록 조회")
	public ApiResponse<AdminClassroomListResponse> list(
		@RequestParam(defaultValue = "RECENT") AdminListSort sort,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(adminClassroomService.list(sort, page, size));
	}

	@GetMapping("/{id}")
	@Operation(summary = "관리자 강의실 상세 조회")
	public ApiResponse<AdminClassroomDetailResponse> detail(
		@PathVariable("id") Long classroomId
	) {
		return ApiResponse.success(adminClassroomService.detail(classroomId));
	}
}
