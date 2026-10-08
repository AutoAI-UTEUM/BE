package io.edupilot.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI eduPilotOpenApi() {
		return new OpenAPI()
			.components(new Components().addSecuritySchemes(
				"bearerAuth",
				new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
			))
			.info(new Info()
				.title("EduPilot Main Service API")
				.version("0.1.0")
				.description("EduPilot Frontend가 호출하는 Spring 외부 API. 신규 DOB는 Asia/Seoul 오늘 이하로 제한합니다. 한국 현재 연도−출생 연도 ≥15는 보호자 불필요, ≤14·DOB 미확인은 이메일 확인 후에도 UNKNOWN이면 AGE_VERIFICATION_REQUIRED(403), MANUAL_PENDING이면 GUARDIAN_VERIFICATION_PENDING(403)입니다. 본인 DOB 관리자 수정 요청은 접수만 하며 DOB·이용 자격을 변경하지 않습니다. 로그인·refresh·본인 관리·필수동의 제한과 LEGACY_EXEMPT 기존 이용 예외를 유지합니다."));
	}
}
