package io.edupilot.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.util.StringUtils;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public record WithdrawRequest(
	@Size(max = 128)
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, example = "password123")
	String password,
	@Size(max = 8192)
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
	String googleIdToken
) {
	public WithdrawRequest(String password) {
		this(password, null);
	}

	@JsonIgnore
	@AssertTrue(message = "비밀번호 또는 Google ID 토큰 중 하나만 제출해야 합니다.")
	public boolean isSingleCredential() {
		return StringUtils.hasText(password) != StringUtils.hasText(googleIdToken);
	}
}
