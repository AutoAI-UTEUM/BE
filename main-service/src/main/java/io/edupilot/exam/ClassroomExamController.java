package io.edupilot.exam;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.exam.dto.CreateExamRequest;
import io.edupilot.exam.dto.InstructorExamDetailResponse;
import io.edupilot.exam.dto.InstructorExamListResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/classrooms/{classroomId}/exams")
@Validated
@Tag(name = "Exams")
@SecurityRequirement(name = "bearerAuth")
public class ClassroomExamController {

	private final InstructorExamService instructorExamService;

	public ClassroomExamController(InstructorExamService instructorExamService) {
		this.instructorExamService = instructorExamService;
	}

	@PostMapping
	@Operation(summary = "시험 초안 생성")
	public ApiResponse<InstructorExamDetailResponse> create(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@Valid @RequestBody CreateExamRequest request
	) {
		return ApiResponse.success(instructorExamService.create(
			user.userId(), user.role(), classroomId, request
		));
	}

	@GetMapping
	@Operation(summary = "강사 시험 목록 조회")
	public ApiResponse<InstructorExamListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long classroomId,
		@RequestParam(required = false) ExamStatus status,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(instructorExamService.list(
			user.userId(), user.role(), classroomId, status, page, size
		));
	}
}
