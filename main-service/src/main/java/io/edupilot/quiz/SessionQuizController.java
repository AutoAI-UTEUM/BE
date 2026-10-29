package io.edupilot.quiz;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.quiz.dto.QuizListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Validated
@RequestMapping("/api/sessions/{sessionId}/quizzes")
@Tag(name = "Quizzes")
@SecurityRequirement(name = "bearerAuth")
public class SessionQuizController {

	private final QuizService quizService;

	public SessionQuizController(QuizService quizService) {
		this.quizService = quizService;
	}

	@GetMapping
	@Operation(summary = "세션 퀴즈 기록 최신순 페이지 조회")
	public ApiResponse<QuizListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "100") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(
			quizService.list(authenticatedUser.userId(), sessionId, page, size)
		);
	}
}
