package io.edupilot.admin;

import java.time.Clock;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import io.edupilot.auth.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminAuditInterceptor implements HandlerInterceptor {
	private static final Logger log = LoggerFactory.getLogger(AdminAuditInterceptor.class);
	private final Clock clock;

	public AdminAuditInterceptor(Clock clock) {
		this.clock = clock;
	}

	@Override
	public void afterCompletion(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler,
		Exception exception
	) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Object principal = authentication == null ? null : authentication.getPrincipal();
		Long actorUserId = principal instanceof AuthenticatedUser user ? user.userId() : null;
		String action = handler instanceof HandlerMethod method
			? action(method) : "UNMAPPED_ADMIN_REQUEST";
		String endpoint = request.getRequestURI();
		String targetType = targetType(endpoint);
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		Object targetId = variables instanceof Map<?, ?> map ? map.get("id") : null;
		var event = log.atInfo()
			.addKeyValue("actorUserId", actorUserId)
			.addKeyValue("action", action)
			.addKeyValue("targetType", targetType)
			.addKeyValue("targetId", targetId)
			.addKeyValue("method", request.getMethod())
			.addKeyValue("endpoint", endpoint)
			.addKeyValue("status", response.getStatus())
			.addKeyValue("occurredAt", clock.instant());
		if (request.getAttribute("adminAuditBefore") != null) {
			event.addKeyValue("before", request.getAttribute("adminAuditBefore"));
			event.addKeyValue("after", request.getAttribute("adminAuditAfter"));
		}
		event.log("Admin API accessed");
	}

	private String action(HandlerMethod method) {
		AdminAction annotation = method.getMethodAnnotation(AdminAction.class);
		return annotation == null ? method.getMethod().getName() : annotation.value();
	}

	private String targetType(String endpoint) {
		String suffix = endpoint.substring("/api/admin/".length());
		int slash = suffix.indexOf('/');
		String resource = slash < 0 ? suffix : suffix.substring(0, slash);
		return switch (resource) {
			case "users" -> "USER";
			case "classrooms" -> "CLASSROOM";
			default -> resource.toUpperCase();
		};
	}
}
