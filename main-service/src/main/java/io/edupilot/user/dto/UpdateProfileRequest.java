package io.edupilot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
	@Size(max = 100, message = "이름은 100자 이하여야 합니다.")
	@Schema(example = "홍길동")
	String name,

	@Size(max = 100, message = "소속은 100자 이하여야 합니다.")
	@Schema(example = "EduPilot University")
	String affiliation
) {
}
