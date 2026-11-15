package io.edupilot.quiz;

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
import io.edupilot.quiz.dto.QuizDraftConflictResponse;
import io.edupilot.quiz.dto.QuizDraftResponse;
import io.edupilot.quiz.dto.SaveQuizDraftRequest;
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
@RequestMapping("/api/quizzes/{quizId}/draft")
@Tag(name = "Quizzes")
@SecurityRequirement(name = "bearerAuth")
public class QuizDraftController {

	private final QuizDraftService service;

	public QuizDraftController(QuizDraftService service) {
		this.service = service;
	}

	@PutMapping
	@Operation(summary = "학습 퀴즈 답안 임시저장")
	public ApiResponse<QuizDraftResponse> save(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long quizId,
		@Valid @RequestBody SaveQuizDraftRequest request
	) {
		return ApiResponse.success(service.save(user.userId(), quizId, request));
	}

	@GetMapping
	@Operation(summary = "학습 퀴즈 답안 임시저장 조회")
	public ResponseEntity<ApiResponse<QuizDraftResponse>> get(
		@AuthenticationPrincipal AuthenticatedUser user,
		@PathVariable Long quizId
	) {
		QuizDraftResponse draft = service.get(user.userId(), quizId);
		return draft == null
			? ResponseEntity.noContent().build()
			: ResponseEntity.ok(ApiResponse.success(draft));
	}

	@ExceptionHandler(QuizDraftVersionConflictException.class)
	public ResponseEntity<QuizDraftConflictResponse> handleVersionConflict(
		QuizDraftVersionConflictException exception,
		HttpServletRequest request
	) {
		ErrorCode code = exception.errorCode();
		Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
		return ResponseEntity.status(HttpStatus.CONFLICT).body(
			new QuizDraftConflictResponse(
				false,
				new ApiError(code.code(), code.message(), List.of()),
				exception.latestDraft(),
				traceId == null ? "unknown" : traceId.toString(),
				Instant.now()
			)
		);
	}
}
