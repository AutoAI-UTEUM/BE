package io.edupilot.classroom.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomNoticeRequest(
	@NotBlank @Size(max = 200) String title,
	@NotBlank String content,
	Integer weekNumber,
	Instant publishAt
) {
	public CreateClassroomNoticeRequest(String title, String content) {
		this(title, content, null, null);
	}
}
