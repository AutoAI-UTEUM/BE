package io.edupilot.quiz;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.quiz.dto.QuizListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/sessions/{sessionId}/quizzes")
@Tag(name = "Quizzes")
@SecurityRequirement(name = "bearerAuth")
public class SessionQuizController {

	private final QuizService quizService;

	public SessionQuizController(QuizService quizService) {
		this.quizService = quizService;
	}

	@GetMapping
	@Operation(summary = "세션 퀴즈 기록 최신 100건 조회")
	public ApiResponse<QuizListResponse> list(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long sessionId
	) {
		return ApiResponse.success(
			quizService.list(authenticatedUser.userId(), sessionId)
		);
	}
}
