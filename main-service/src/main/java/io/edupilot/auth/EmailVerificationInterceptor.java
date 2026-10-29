package io.edupilot.auth;

import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import io.edupilot.global.error.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class EmailVerificationInterceptor implements HandlerInterceptor {
	private final EmailVerificationGate gate;
	private final SecurityErrorResponseWriter errors;
	public EmailVerificationInterceptor(EmailVerificationGate gate, SecurityErrorResponseWriter errors) {
		this.gate = gate;
		this.errors = errors;
	}
	@Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
			return true; // Existing authentication rules decide unauthenticated access.
		}
		try {
			gate.requireVerified(user.userId());
			return true;
		} catch (BusinessException rejected) {
			response.setHeader("Cache-Control", "no-store");
			errors.write(request, response, rejected.errorCode());
			return false;
		}
	}
}
