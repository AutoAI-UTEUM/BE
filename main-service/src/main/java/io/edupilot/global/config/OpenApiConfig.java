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
				.description("EduPilot Frontend가 호출하는 Spring 외부 API"));
	}
}
