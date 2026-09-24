package io.edupilot.auth.dto;

import io.edupilot.auth.validation.ValidPassword;
import io.swagger.v3.oas.annotations.media.Schema;

public record PasswordResetConfirmRequest(
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
	String token,
	@ValidPassword
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
	String newPassword
) {
}
