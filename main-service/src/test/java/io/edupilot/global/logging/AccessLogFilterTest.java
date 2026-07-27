package io.edupilot.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AccessLogFilterTest {

	private static final String SECRET_SENTINEL = "never-log-this-secret";

	private final AccessLogFilter filter = new AccessLogFilter();
	private final Logger logger = (Logger) LoggerFactory.getLogger(
		AccessLogFilter.class
	);
	private ListAppender<ILoggingEvent> appender;

	@BeforeEach
	void setUp() {
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	void logsOnlyPatternedRequestMetadata() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(
			"POST",
			"/api/sessions/42/turns"
		);
		request.setQueryString("token=" + SECRET_SENTINEL);
		request.addHeader(
			HttpHeaders.AUTHORIZATION,
			"Bearer " + SECRET_SENTINEL
		);
		request.addHeader(HttpHeaders.COOKIE, "refresh=" + SECRET_SENTINEL);
		request.setContent(SECRET_SENTINEL.getBytes());
		request.setAttribute(
			HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
			"/api/sessions/{sessionId}/turns"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setStatus(202);

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
		});

		assertThat(appender.list).singleElement().satisfies(event -> {
			Map<String, String> fields = event.getKeyValuePairs().stream()
				.collect(Collectors.toMap(
					pair -> pair.key,
					pair -> String.valueOf(pair.value)
				));
			assertThat(fields)
				.containsEntry(
					"endpoint",
					"/api/sessions/{sessionId}/turns"
				)
				.containsEntry("method", "POST")
				.containsEntry("status", "202")
				.containsKey("durationMs");
			assertThat(eventText(event)).doesNotContain(SECRET_SENTINEL);
		});
	}

	@Test
	void doesNotLogHealthRequests() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(
			"GET",
			"/api/health/ready"
		);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
		});

		assertThat(appender.list).isEmpty();
	}

	private String eventText(ILoggingEvent event) {
		return event.getFormattedMessage()
			+ event.getKeyValuePairs()
			+ event.getMDCPropertyMap();
	}
}
