package io.edupilot.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.edupilot.ai.AiClientException;
import io.edupilot.global.response.ApiResponse;
import io.edupilot.global.security.TraceIdFilter;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

class ApiContractTest {

	private static final String TRACE_ID = "contract-test-trace";

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ContractTestController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(new TraceIdFilter())
			.build();
	}

	@Test
	void successFactoryReturnsDefaultEnvelope() throws Exception {
		mockMvc.perform(post("/contract/validate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"learner\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.name").value("learner"))
			.andExpect(jsonPath("$.message").value("요청이 성공했습니다."));
	}

	@Test
	void invalidBodyReturnsValidationDetails() throws Exception {
		mockMvc.perform(post("/contract/validate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.error.details[0].field").value("name"))
			.andExpect(jsonPath("$.error.details[0].reason").value("이름은 필수입니다."))
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(jsonPath("$.timestamp").isString());
	}

	@Test
	void constraintViolationReturnsValidationError() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE, TRACE_ID);

		var response = new GlobalExceptionHandler().handleConstraintViolation(
			new ConstraintViolationException(Set.of()),
			request
		);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_FAILED");
		assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
	}

	@Test
	void malformedJsonReturnsStableErrorCode() throws Exception {
		mockMvc.perform(post("/contract/validate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
			.andExpect(jsonPath("$.error.details").isArray());
	}

	@Test
	void unsupportedContentTypeReturnsStableErrorCode() throws Exception {
		mockMvc.perform(post("/contract/validate")
				.contentType(MediaType.TEXT_PLAIN)
				.content("name=learner"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void businessExceptionUsesMappedStatusCodeAndSafeMessage() throws Exception {
		mockMvc.perform(get("/contract/denied"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"))
			.andExpect(jsonPath("$.error.message").value("이 기능에 접근할 수 없습니다."));
	}

	@Test
	void unexpectedExceptionHidesInternalDetails() throws Exception {
		mockMvc.perform(get("/contract/failure"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."))
			.andExpect(jsonPath("$.error.details").isEmpty())
			.andExpect(jsonPath("$.traceId").isNotEmpty())
			.andExpect(content().string(not(containsString("internal-only-detail"))));
	}

	@Test
	void aiClientExceptionUsesCommonEnvelopeWithoutRemoteDetails() throws Exception {
		mockMvc.perform(get("/contract/ai-failure"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
			.andExpect(jsonPath("$.error.message")
				.value(ErrorCode.INTERNAL_SERVER_ERROR.message()))
			.andExpect(content().string(not(containsString("remote-body"))));
	}

	@RestController
	@RequestMapping("/contract")
	private static class ContractTestController {

		@PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
		ApiResponse<ContractRequest> validate(@Valid @RequestBody ContractRequest request) {
			return ApiResponse.success(request);
		}

		@GetMapping("/denied")
		void denied() {
			throw new BusinessException(ErrorCode.ACCESS_DENIED, "이 기능에 접근할 수 없습니다.");
		}

		@GetMapping("/failure")
		void fail() {
			throw new IllegalStateException("internal-only-detail");
		}

		@GetMapping("/ai-failure")
		void aiFailure() {
			throw new AiClientException(
				ErrorCode.INTERNAL_SERVER_ERROR,
				new IllegalStateException("remote-body")
			);
		}
	}

	private record ContractRequest(
		@NotBlank(message = "이름은 필수입니다.") String name
	) {
	}
}
