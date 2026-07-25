package io.edupilot.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.response.ErrorResponse;
import io.edupilot.global.security.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorResponseWriter {

	private final ObjectMapper objectMapper;

	public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void write(
		HttpServletRequest request,
		HttpServletResponse response,
		ErrorCode errorCode
	) throws IOException {
		response.setStatus(errorCode.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		Object traceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
		ErrorResponse body = ErrorResponse.failure(
			errorCode.code(),
			errorCode.message(),
			List.of(),
			traceId == null ? "unknown" : traceId.toString()
		);
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
