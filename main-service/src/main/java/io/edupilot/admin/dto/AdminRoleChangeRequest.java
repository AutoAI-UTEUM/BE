package io.edupilot.admin.dto;

import io.edupilot.user.UserRole;
import jakarta.validation.constraints.NotNull;

public record AdminRoleChangeRequest(@NotNull UserRole role) {
}
