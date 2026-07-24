package io.edupilot.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI eduPilotOpenApi() {
		return new OpenAPI().info(new Info()
			.title("EduPilot Main Service API")
			.version("0.1.0")
			.description("EduPilot Frontend가 호출하는 Spring 외부 API"));
	}
}
