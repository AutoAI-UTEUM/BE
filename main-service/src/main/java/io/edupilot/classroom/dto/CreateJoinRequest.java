package io.edupilot.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateJoinRequest(
	@NotBlank @Size(max = 16) String inviteCode
) {
}
