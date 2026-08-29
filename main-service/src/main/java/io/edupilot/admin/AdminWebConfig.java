package io.edupilot.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

	private final AdminDbRoleInterceptor adminDbRoleInterceptor;

	public AdminWebConfig(AdminDbRoleInterceptor adminDbRoleInterceptor) {
		this.adminDbRoleInterceptor = adminDbRoleInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminDbRoleInterceptor)
			.addPathPatterns("/api/admin/**");
	}
}
