package io.edupilot.auth.dto;

import io.edupilot.auth.validation.ValidEmail;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@ValidEmail
	@Schema(example = "user@example.com")
	String email,

	@NotBlank(message = "비밀번호는 필수입니다.")
	@Pattern(
		regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
		message = "비밀번호는 8~64자이며 영문과 숫자를 각각 하나 이상 포함해야 합니다."
	)
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, example = "password123")
	String password,

	@NotBlank(message = "이름은 필수입니다.")
	@Size(max = 100, message = "이름은 100자 이하여야 합니다.")
	@Schema(example = "홍길동")
	String name,

	@NotNull(message = "역할은 필수입니다.")
	@Schema(example = "LEARNER")
	SignupRole role
) {
}
