package io.edupilot.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomLinkResourceRequest(
	@NotBlank @Size(max = 2048) String url,
	@NotBlank @Size(max = 200) String title,
	Integer weekNumber
) {
}
