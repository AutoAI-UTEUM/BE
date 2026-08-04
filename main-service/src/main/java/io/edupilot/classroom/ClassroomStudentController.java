package io.edupilot.classroom;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.classroom.dto.ClassroomStudentListResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/classrooms/{classroomId}/students")
@Validated
@Tag(name = "Classroom students")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomStudentController {

	private final ClassroomStudentService studentService;

	public ClassroomStudentController(ClassroomStudentService studentService) {
		this.studentService = studentService;
	}

	@GetMapping
	@Operation(summary = "강의실 수강생 목록 조회")
	public ApiResponse<ClassroomStudentListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(studentService.list(
			user.userId(), user.role(), classroomId, page, size
		));
	}

	@DeleteMapping("/{studentId}")
	@Operation(summary = "강의실 수강생 제외")
	public ApiResponse<Void> remove(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@PathVariable Long studentId
	) {
		studentService.remove(
			user.userId(), user.role(), classroomId, studentId
		);
		return ApiResponse.success(null);
	}
}
