package io.edupilot.auth.dto;

import java.util.List;

import io.edupilot.policy.dto.PolicyConsentChoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GoogleLoginRequest(
	@NotBlank(message = "Google ID 토큰은 필수입니다.")
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
	String idToken,

	@Size(max = 20, message = "역할은 20자 이하여야 합니다.")
	@Schema(example = "LEARNER")
	String role,

	@Schema(description = "신규 가입 시 현재 TERMS·PRIVACY 버전 동의. 서버 설정에 따라 필수 여부가 달라집니다.")
	List<PolicyConsentChoice> consents,

	@Schema(defaultValue = "false")
	Boolean learningEmailOptIn,

	@Size(max = 100, message = "소속은 100자 이하여야 합니다.")
	@Schema(example = "EduPilot University")
	String affiliation
) {
}
