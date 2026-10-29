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

	@Schema(description = "신규 가입 시 현재 requiresConsent=true인 정책 버전 동의. 필수 설정에서 유효한 대상이 없으면 SIGNUP_POLICY_NOT_READY(503), 준비된 대상의 동의 오류는 POLICY_CONSENT_REQUIRED(400)입니다. 기존 Google 로그인은 가입 동의 검증을 하지 않습니다.")
	List<PolicyConsentChoice> consents,

	@Schema(defaultValue = "false")
	Boolean learningEmailOptIn,

	@Size(max = 100, message = "소속은 100자 이하여야 합니다.")
	@Schema(example = "EduPilot University")
	String affiliation,

	@Schema(accessMode = Schema.AccessMode.WRITE_ONLY, type = "string", format = "date",
		description = "새 Google 계정 생성에만 필수. Asia/Seoul 오늘 이후는 VALIDATION_FAILED(400). 기존 Google 로그인은 제출한 날짜를 검증·반영하지 않고 기존 DOB를 변경하지 않습니다. 보호자 대상 연도 기준은 LOCAL과 같습니다.")
	java.time.LocalDate dateOfBirth
) {
}
