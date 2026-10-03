package io.edupilot.auth.dto;

import java.util.List;

import io.edupilot.auth.validation.ValidEmail;
import io.edupilot.auth.validation.ValidPassword;
import io.edupilot.policy.dto.PolicyConsentChoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@ValidEmail
	@Schema(example = "user@example.com")
	String email,

	@ValidPassword
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, example = "password123")
	String password,

	@NotBlank(message = "이름은 필수입니다.")
	@Size(max = 100, message = "이름은 100자 이하여야 합니다.")
	@Schema(example = "홍길동")
	String name,

	@NotNull(message = "역할은 필수입니다.")
	@Schema(example = "LEARNER")
	SignupRole role,

	@Size(max = 100, message = "소속은 100자 이하여야 합니다.")
	@Schema(example = "EduPilot University")
	String affiliation,

	@Schema(defaultValue = "false")
	Boolean learningEmailOptIn,

	@Schema(description = "현재 TERMS·PRIVACY 버전 동의. 서버 설정에 따라 필수 여부가 달라집니다.")
	List<PolicyConsentChoice> consents,

	@NotNull(message = "신규 가입에는 생년월일이 필요합니다.")
	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, type = "string", format = "date",
		description = "신규 가입의 사용자 입력 생년월일. 입력만으로 연령 또는 보호자 확인이 완료되지 않습니다.")
	java.time.LocalDate dateOfBirth
) {
}
