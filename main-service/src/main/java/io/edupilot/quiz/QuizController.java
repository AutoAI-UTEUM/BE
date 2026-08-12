package io.edupilot.quiz;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.quiz.dto.QuizDetailResponse;
import io.edupilot.quiz.dto.QuizSubmissionDetailResponse;
import io.edupilot.quiz.dto.QuizSubmitRequest;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/quizzes")
@Tag(name = "Quizzes")
@SecurityRequirement(name = "bearerAuth")
public class QuizController {

	private final QuizService quizService;
	private final QuizSubmissionService submissionService;

	public QuizController(
		QuizService quizService,
		QuizSubmissionService submissionService
	) {
		this.quizService = quizService;
		this.submissionService = submissionService;
	}

	@GetMapping("/{quizId}")
	@Operation(summary = "퀴즈 공개 문항 조회")
	public ApiResponse<QuizDetailResponse> detail(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long quizId
	) {
		return ApiResponse.success(
			quizService.detail(authenticatedUser.userId(), quizId)
		);
	}

	@GetMapping("/{quizId}/submission")
	@Operation(summary = "내 퀴즈 제출 결과 조회")
	public ApiResponse<QuizSubmissionDetailResponse> submission(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long quizId
	) {
		return ApiResponse.success(submissionService.detail(
			authenticatedUser.userId(),
			quizId
		));
	}

	@PostMapping("/{quizId}/submit")
	@Operation(summary = "퀴즈 답안 제출 및 채점")
	public ApiResponse<QuizSubmitResponse> submit(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@PathVariable Long quizId,
		@RequestBody QuizSubmitRequest request
	) {
		return ApiResponse.success(submissionService.submit(
			authenticatedUser.userId(),
			quizId,
			request
		));
	}
}
