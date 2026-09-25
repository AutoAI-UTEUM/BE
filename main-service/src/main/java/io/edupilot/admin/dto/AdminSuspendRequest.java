package io.edupilot.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminSuspendRequest(@NotBlank @Size(max = 500) String reason) {
}
