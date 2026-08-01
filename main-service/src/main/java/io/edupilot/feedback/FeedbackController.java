package io.edupilot.feedback;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.feedback.dto.CreateFeedbackRequest;
import io.edupilot.feedback.dto.FeedbackResponse;
import io.edupilot.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@Tag(name = "Feedback")
@SecurityRequirement(name = "bearerAuth")
public class FeedbackController {

	private final FeedbackService feedbackService;

	public FeedbackController(FeedbackService feedbackService) {
		this.feedbackService = feedbackService;
	}

	@PostMapping("/api/feedback")
	@Operation(summary = "피드백 접수")
	public ApiResponse<FeedbackResponse> create(
		@AuthenticationPrincipal AuthenticatedUser authenticatedUser,
		@Valid @RequestBody CreateFeedbackRequest request
	) {
		return ApiResponse.success(feedbackService.create(
			authenticatedUser.userId(),
			request
		));
	}
}
