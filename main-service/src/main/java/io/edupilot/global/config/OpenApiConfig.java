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
				.description("EduPilot Frontend가 호출하는 Spring 외부 API. 신규 계정의 보호 업무 API는 이메일 확인 후에도 연령 UNKNOWN이면 AGE_VERIFICATION_REQUIRED(403), 보호자 접수 MANUAL_PENDING이면 GUARDIAN_VERIFICATION_PENDING(403)으로 거부합니다. 로그인·refresh·본인 관리·동의는 유지하며 LEGACY_EXEMPT는 확인 증거와 별개의 기존 이용 예외입니다."));
	}
}
