package io.edupilot.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class TraceIdFilterTest {

	private final TraceIdFilter filter = new TraceIdFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void reusesValidHeaderAndAddsItToResponse() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "client-trace_123");
		AtomicReference<String> traceDuringRequest = new AtomicReference<>();

		filter.doFilter(request, response, (servletRequest, servletResponse) ->
			traceDuringRequest.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY))
		);

		assertThat(traceDuringRequest.get()).isEqualTo("client-trace_123");
		assertThat(request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE))
			.isEqualTo("client-trace_123");
		assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
			.isEqualTo("client-trace_123");
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
	}

	@Test
	void generatesUuidWhenHeaderIsMissingOrInvalid() throws Exception {
		assertGeneratedTraceId(null);
		assertGeneratedTraceId(" invalid trace ");
		assertGeneratedTraceId("x".repeat(129));
	}

	@Test
	void clearsMdcWhenDownstreamFilterFails() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		assertThatThrownBy(() ->
			filter.doFilter(request, response, (servletRequest, servletResponse) -> {
				throw new ServletException("expected");
			})
		).isInstanceOf(ServletException.class);

		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
		assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
			.isNotBlank();
	}

	private void assertGeneratedTraceId(String header) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		if (header != null) {
			request.addHeader(TraceIdFilter.TRACE_ID_HEADER, header);
		}

		filter.doFilter(request, response, (servletRequest, servletResponse) -> {
		});

		String generated = response.getHeader(TraceIdFilter.TRACE_ID_HEADER);
		assertThat(generated).isNotEqualTo(header);
		assertThat(UUID.fromString(generated)).isNotNull();
		assertThat(request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE))
			.isEqualTo(generated);
		assertThat(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)).isNull();
	}
}
