package io.edupilot.admin;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.SecurityErrorResponseWriter;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AdminDbRoleInterceptor implements HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(
		AdminDbRoleInterceptor.class
	);

	private final UserRepository userRepository;
	private final SecurityErrorResponseWriter errorResponseWriter;

	public AdminDbRoleInterceptor(
		UserRepository userRepository,
		SecurityErrorResponseWriter errorResponseWriter
	) {
		this.userRepository = userRepository;
		this.errorResponseWriter = errorResponseWriter;
	}

	@Override
	public boolean preHandle(
		HttpServletRequest request,
		HttpServletResponse response,
		Object handler
	) throws IOException {
		Authentication authentication = SecurityContextHolder.getContext()
			.getAuthentication();
		Object principal = authentication == null ? null : authentication.getPrincipal();
		if (!(principal instanceof AuthenticatedUser authenticatedUser)) {
			return deny(
				request,
				response,
				null,
				DenialReason.INVALID_PRINCIPAL
			);
		}

		User user = userRepository.findById(authenticatedUser.userId()).orElse(null);
		if (user == null) {
			return deny(
				request,
				response,
				authenticatedUser.userId(),
				DenialReason.USER_NOT_FOUND
			);
		}
		if (user.getRole() != UserRole.ADMIN) {
			return deny(
				request,
				response,
				authenticatedUser.userId(),
				DenialReason.ROLE_MISMATCH
			);
		}
		if (user.getStatus() != UserStatus.ACTIVE) {
			return deny(
				request,
				response,
				authenticatedUser.userId(),
				DenialReason.INACTIVE_STATUS
			);
		}
		return true;
	}

	private boolean deny(
		HttpServletRequest request,
		HttpServletResponse response,
		Long userId,
		DenialReason reason
	) throws IOException {
		log.atWarn()
			.addKeyValue("userId", userId)
			.addKeyValue("reason", reason)
			.log("Admin DB role verification rejected");
		errorResponseWriter.write(request, response, ErrorCode.ACCESS_DENIED);
		return false;
	}

	private enum DenialReason {
		INVALID_PRINCIPAL,
		USER_NOT_FOUND,
		ROLE_MISMATCH,
		INACTIVE_STATUS
	}
}
