package io.edupilot.exam;

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
import io.edupilot.exam.dto.ExamSubmissionResponse;
import io.edupilot.exam.dto.InstructorExamDetailResponse;
import io.edupilot.exam.dto.InstructorSubmissionListResponse;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.exam.dto.UpdateExamRequest;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/exams")
@Validated
@Tag(name = "Exams")
@SecurityRequirement(name = "bearerAuth")
public class ExamController {

	private final InstructorExamService instructorExamService;
	private final StudentExamService studentExamService;

	public ExamController(
		InstructorExamService instructorExamService,
		StudentExamService studentExamService
	) {
		this.instructorExamService = instructorExamService;
		this.studentExamService = studentExamService;
	}

	@GetMapping("/{examId}")
	@Operation(summary = "강사 시험 상세 조회")
	public ApiResponse<?> detail(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId
	) {
		if (user.role() == io.edupilot.user.UserRole.LEARNER) {
			return ApiResponse.success(studentExamService.detail(
				user.userId(), user.role(), examId
			));
		}
		return ApiResponse.success(instructorExamService.detail(
			user.userId(), user.role(), examId
		));
	}

	@PatchMapping("/{examId}")
	@Operation(summary = "시험 초안 수정")
	public ApiResponse<InstructorExamDetailResponse> update(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId,
		@Valid @RequestBody UpdateExamRequest request
	) {
		return ApiResponse.success(instructorExamService.update(
			user.userId(), user.role(), examId, request
		));
	}

	@PostMapping("/{examId}/publish")
	@Operation(summary = "시험 공개")
	public ApiResponse<InstructorExamDetailResponse> publish(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId
	) {
		return ApiResponse.success(instructorExamService.publish(
			user.userId(), user.role(), examId
		));
	}

	@PostMapping("/{examId}/close")
	@Operation(summary = "시험 마감")
	public ApiResponse<InstructorExamDetailResponse> close(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId
	) {
		return ApiResponse.success(instructorExamService.close(
			user.userId(), user.role(), examId
		));
	}

	@DeleteMapping("/{examId}")
	@Operation(summary = "시험 초안 삭제")
	public ApiResponse<Void> delete(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId
	) {
		instructorExamService.delete(user.userId(), user.role(), examId);
		return ApiResponse.success(null);
	}

	@GetMapping("/{examId}/submissions")
	@Operation(summary = "시험 제출 목록 조회")
	public ApiResponse<InstructorSubmissionListResponse> submissions(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(instructorExamService.submissions(
			user.userId(), user.role(), examId, page, size
		));
	}

	@GetMapping("/{examId}/submissions/{submissionId}")
	@Operation(summary = "시험 제출 상세 조회")
	public ApiResponse<ExamSubmissionResponse> submissionDetail(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId,
		@PathVariable Long submissionId
	) {
		return ApiResponse.success(instructorExamService.submissionDetail(
			user.userId(), user.role(), examId, submissionId
		));
	}

	@PostMapping("/{examId}/submissions")
	@Operation(summary = "시험 제출")
	public ApiResponse<ExamSubmissionResponse> submit(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId,
		@Valid @RequestBody SubmitExamRequest request
	) {
		return ApiResponse.success(studentExamService.submit(
			user.userId(), user.role(), examId, request
		));
	}

	@GetMapping("/{examId}/submissions/me")
	@Operation(summary = "내 시험 결과 조회")
	public ApiResponse<ExamSubmissionResponse> mySubmission(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId,
		@RequestParam(required = false) @Min(1) Integer attemptNo
	) {
		return ApiResponse.success(studentExamService.mySubmission(
			user.userId(), user.role(), examId, attemptNo
		));
	}
}
