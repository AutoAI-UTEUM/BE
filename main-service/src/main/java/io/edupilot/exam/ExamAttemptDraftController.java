package io.edupilot.exam;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.exam.dto.ExamAttemptDraftConflictResponse;
import io.edupilot.exam.dto.ExamAttemptDraftResponse;
import io.edupilot.exam.dto.ExamAttemptDraftSaveResponse;
import io.edupilot.exam.dto.SaveExamAttemptDraftRequest;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.response.ApiError;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.global.security.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/exams/{examId}/attempts/draft")
@Tag(name = "Exams")
@SecurityRequirement(name = "bearerAuth")
public class ExamAttemptDraftController {

	private final ExamAttemptDraftService service;

	public ExamAttemptDraftController(ExamAttemptDraftService service) {
		this.service = service;
	}

	@PutMapping
	@Operation(summary = "시험 답안 임시저장")
	public ApiResponse<ExamAttemptDraftSaveResponse> save(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId,
		@Valid @RequestBody SaveExamAttemptDraftRequest request
	) {
		return ApiResponse.success(service.save(user.userId(), user.role(), examId, request));
	}

	@GetMapping
	@Operation(summary = "시험 답안 임시저장 조회")
	public ResponseEntity<ApiResponse<ExamAttemptDraftResponse>> get(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long examId
	) {
		ExamAttemptDraftResponse draft = service.get(user.userId(), user.role(), examId);
		return draft == null
			? ResponseEntity.noContent().build()
			: ResponseEntity.ok(ApiResponse.success(draft));
	}

	@ExceptionHandler(ExamDraftVersionConflictException.class)
	public ResponseEntity<ExamAttemptDraftConflictResponse> handleVersionConflict(
		ExamDraftVersionConflictException exception,
		HttpServletRequest request
	) {
		ErrorCode code = exception.errorCode();
		Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(
			new ExamAttemptDraftConflictResponse(
				false,
				new ApiError(code.code(), code.message(), List.of()),
				exception.latestDraft(),
				traceId == null ? "unknown" : traceId.toString(),
				Instant.now()
			)
		);
	}
}
