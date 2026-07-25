package io.edupilot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record WithdrawRequest(
	@NotBlank(message = "비밀번호는 필수입니다.")
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, example = "password123")
	String password
) {
}
