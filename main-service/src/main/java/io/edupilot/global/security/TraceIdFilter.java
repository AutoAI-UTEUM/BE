package io.edupilot.global.security;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String TRACE_ID_HEADER = "X-Trace-Id";
	public static final String TRACE_ID_ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
	public static final String TRACE_ID_MDC_KEY = "traceId";
	private static final Pattern VALID_TRACE_ID =
		Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));

		request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
		response.setHeader(TRACE_ID_HEADER, traceId);
		MDC.put(TRACE_ID_MDC_KEY, traceId);

		try {
			filterChain.doFilter(request, response);
		} finally {
			MDC.remove(TRACE_ID_MDC_KEY);
		}
	}

	private String resolveTraceId(String candidate) {
		if (candidate != null && VALID_TRACE_ID.matcher(candidate).matches()) {
			return candidate;
		}
		return UUID.randomUUID().toString();
	}
}
