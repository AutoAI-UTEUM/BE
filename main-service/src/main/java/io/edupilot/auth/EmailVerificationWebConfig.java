package io.edupilot.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class EmailVerificationWebConfig implements WebMvcConfigurer {
	private final EmailVerificationInterceptor interceptor;
	public EmailVerificationWebConfig(EmailVerificationInterceptor interceptor) { this.interceptor = interceptor; }
	@Override public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(interceptor).order(100).addPathPatterns("/api/**")
			.excludePathPatterns("/api/auth/**", "/api/health", "/api/health/**", "/api/policies/**",
				"/api/users/me", "/api/users/me/password", "/api/users/me/preferences",
				"/api/users/me/avatar", "/api/users/me/consents", "/api/users/me/birthdate-correction-requests",
				"/api/users/me/guardian-requests", "/api/users/me/guardian-requests/*/link",
				"/api/users/me/guardian-requests/*/withdraw");
	}
}
