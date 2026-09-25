package io.edupilot.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

	private final AdminDbRoleInterceptor adminDbRoleInterceptor;
	private final AdminAuditInterceptor adminAuditInterceptor;

	public AdminWebConfig(
		AdminDbRoleInterceptor adminDbRoleInterceptor,
		AdminAuditInterceptor adminAuditInterceptor
	) {
		this.adminDbRoleInterceptor = adminDbRoleInterceptor;
		this.adminAuditInterceptor = adminAuditInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminAuditInterceptor)
			.addPathPatterns("/api/admin/**");
		registry.addInterceptor(adminDbRoleInterceptor)
			.addPathPatterns("/api/admin/**");
	}
}
