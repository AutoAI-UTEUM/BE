package io.edupilot.feedback.dto;

import io.edupilot.feedback.FeedbackCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateFeedbackRequest(
	@NotNull(message = "피드백 카테고리는 필수입니다.")
	FeedbackCategory category,
	@NotBlank(message = "피드백 내용은 필수입니다.")
	@Size(max = 2000, message = "피드백 내용은 2000자 이하여야 합니다.")
	String message,
	String pageUrl,
	String clientVersion
) {
}
