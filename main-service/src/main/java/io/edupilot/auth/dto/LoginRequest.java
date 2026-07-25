package io.edupilot.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank(message = "이메일은 필수입니다.")
	@Email(message = "이메일 형식을 확인해 주세요.")
	@Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
	@Schema(example = "user@example.com")
	String email,

	@NotBlank(message = "비밀번호는 필수입니다.")
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, example = "password123")
	String password
) {
}
