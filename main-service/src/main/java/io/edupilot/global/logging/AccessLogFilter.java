package io.edupilot.global.logging;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);
	private static final String UNMATCHED_ENDPOINT = "UNMATCHED";

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		long startedAt = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} finally {
			if (!isHealthRequest(request)) {
				log.atInfo()
					.addKeyValue("endpoint", endpoint(request))
					.addKeyValue("method", request.getMethod())
					.addKeyValue("status", response.getStatus())
					.addKeyValue(
						"durationMs",
						TimeUnit.NANOSECONDS.toMillis(
							System.nanoTime() - startedAt
						)
					)
					.log("HTTP request completed");
			}
		}
	}

	private boolean isHealthRequest(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/api/health");
	}

	private String endpoint(HttpServletRequest request) {
		Object pattern = request.getAttribute(
			HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
		);
		return pattern == null ? UNMATCHED_ENDPOINT : pattern.toString();
	}
}
