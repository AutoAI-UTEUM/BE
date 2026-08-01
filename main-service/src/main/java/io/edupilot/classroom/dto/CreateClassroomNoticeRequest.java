package io.edupilot.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomNoticeRequest(
	@NotBlank @Size(max = 200) String title,
	@NotBlank String content
) {
}
