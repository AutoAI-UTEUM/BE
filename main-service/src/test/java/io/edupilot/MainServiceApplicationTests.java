package io.edupilot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.CorsFilter;

import io.edupilot.global.security.TraceIdFilter;

@SpringBootTest
@ActiveProfiles("test")
class MainServiceApplicationTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private CorsFilter corsFilter;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.addFilters(traceIdFilter, corsFilter)
			.build();
	}

	@Test
	void contextLoadsAndHealthUsesSuccessEnvelope() throws Exception {
		mockMvc.perform(get("/api/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.status").value("UP"))
			.andExpect(jsonPath("$.message").value("요청이 성공했습니다."));
	}

	@Test
	void missingUrlReturnsNotFoundEnvelope() throws Exception {
		mockMvc.perform(get("/api/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(jsonPath("$.timestamp").isString());
	}

	@Test
	void openApiDocumentAndSwaggerUiAreAvailable() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.info.title").value("EduPilot Main Service API"));

		mockMvc.perform(get("/swagger-ui.html"))
			.andExpect(status().is3xxRedirection());
	}

	@Test
	void configuredCorsOriginAllowsCredentialedPreflight() throws Exception {
		mockMvc.perform(options("/api/health")
				.header(HttpHeaders.ORIGIN, "http://localhost:5173")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.header(
					HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
					"Authorization, Content-Type"
				))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
				"http://localhost:5173"
			))
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
				"true"
			));
	}

	@Test
	void unconfiguredCorsOriginIsRejected() throws Exception {
		mockMvc.perform(options("/api/health")
				.header(HttpHeaders.ORIGIN, "https://untrusted.example")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andExpect(status().isForbidden())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}
}
