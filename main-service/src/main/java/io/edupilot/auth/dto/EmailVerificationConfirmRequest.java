package io.edupilot.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailVerificationConfirmRequest(
	@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "유효한 이메일 확인 링크가 필요합니다.")
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
	String token
) { }
