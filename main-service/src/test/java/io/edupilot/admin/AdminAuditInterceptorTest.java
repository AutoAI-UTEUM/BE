package io.edupilot.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.admin.infra.AdminInfraController;
import io.edupilot.admin.mail.AdminMailController;
import io.edupilot.admin.xai.AdminXaiController;
import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.user.UserRole;

class AdminAuditInterceptorTest {
	static Stream<Method> adminMethods() {
		return Stream.of(
			AdminUserController.class,
			AdminClassroomController.class,
			AdminAiUsageController.class,
			AdminInfraController.class,
			AdminXaiController.class,
			AdminMailController.class
		).flatMap(controller -> Arrays.stream(controller.getDeclaredMethods()))
			.filter(method -> method.isAnnotationPresent(GetMapping.class)
				|| method.isAnnotationPresent(PostMapping.class)
				|| method.isAnnotationPresent(PutMapping.class)
				|| method.isAnnotationPresent(PatchMapping.class));
	}

	@ParameterizedTest
	@MethodSource("adminMethods")
	void eachAdminHandlerProducesOneStructuredAuditWithoutBody(Method method) throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditInterceptor.class);
		ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(
					new AuthenticatedUser(7L, UserRole.ADMIN), null, List.of()
				)
			);
			MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/users/9");
			request.setContent("password=secret-token".getBytes());
			request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "9"));
			MockHttpServletResponse response = new MockHttpServletResponse();
			response.setStatus(200);
			new AdminAuditInterceptor(Clock.fixed(
				Instant.parse("2026-09-25T00:00:00Z"), ZoneOffset.UTC
			)).afterCompletion(
				request, response, new HandlerMethod(mock(method.getDeclaringClass()), method), null
			);

			assertThat(appender.list).hasSize(1);
			var fields = appender.list.getFirst().getKeyValuePairs().stream()
				.collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
			AdminAction annotation = method.getAnnotation(AdminAction.class);
			assertThat(fields.get("action"))
				.isEqualTo(annotation == null ? method.getName() : annotation.value());
			assertThat(fields)
				.containsEntry("actorUserId", 7L)
				.containsEntry("targetType", "USER")
				.containsEntry("targetId", "9")
				.containsEntry("method", "POST")
				.containsEntry("endpoint", "/api/admin/users/9")
				.containsEntry("status", 200)
				.containsKey("occurredAt");
			assertThat(appender.list.getFirst().getFormattedMessage() + fields)
				.doesNotContain("secret-token", "password=");
		} finally {
			SecurityContextHolder.clearContext();
			logger.detachAppender(appender);
			appender.stop();
		}
	}
}
