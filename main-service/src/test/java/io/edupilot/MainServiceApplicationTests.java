package io.edupilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClientProperties;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizProperties;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.UserRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class MainServiceApplicationTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private AiClientProperties aiClientProperties;

	@Autowired
	private QuizProperties quizProperties;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private LearningMaterialRepository learningMaterialRepository;

	@MockitoBean
	private MaterialPageRepository materialPageRepository;

	@MockitoBean
	private LearningSessionRepository learningSessionRepository;

	@MockitoBean
	private ChatMessageRepository chatMessageRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository quizSubmissionRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
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
	void quizAndAiPipelineDefaultsMatchAcceptedContracts() {
		assertThat(quizProperties.passRatio())
			.isEqualByComparingTo(new BigDecimal("0.6"));
		assertThat(aiClientProperties.gradeReadTimeout())
			.isEqualTo(Duration.ofSeconds(90));
		assertThat(aiClientProperties.pipelineReadTimeout())
			.isEqualTo(Duration.ofSeconds(45));
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
			.andExpect(jsonPath("$.info.title").value("EduPilot Main Service API"))
			.andExpect(jsonPath(
				"$.components.securitySchemes.bearerAuth.scheme"
			).value("bearer"))
			.andExpect(jsonPath("$.paths['/api/auth/signup'].post").exists())
			.andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
			.andExpect(jsonPath("$.paths['/api/auth/refresh'].post").exists())
			.andExpect(jsonPath("$.paths['/api/auth/logout'].post").exists())
			.andExpect(jsonPath("$.paths['/api/users/me'].get").exists())
			.andExpect(jsonPath("$.paths['/api/sessions'].post").exists())
			.andExpect(jsonPath("$.paths['/api/sessions'].get").exists())
			.andExpect(jsonPath("$.paths['/api/sessions/{sessionId}'].get").exists())
			.andExpect(jsonPath("$.paths['/api/sessions/{sessionId}'].delete").exists())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/page'].patch"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/turns'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/messages'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/complete'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/quizzes'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/quizzes/{quizId}'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/quizzes/{quizId}/submit'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/users/me/memory'].get"
			).exists())
			.andExpect(jsonPath("$.paths['/api/users/me'].delete").exists())
			.andExpect(jsonPath("$.paths['/api/materials'].post").exists())
			.andExpect(jsonPath(
				"$.paths['/api/materials'].post.requestBody.content"
					+ "['multipart/form-data'].schema.properties.file"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/materials'].post.requestBody.content"
					+ "['multipart/form-data'].schema.properties.title"
			).exists())
			.andExpect(jsonPath("$.paths['/api/materials'].get").exists())
			.andExpect(jsonPath("$.paths['/api/materials/{materialId}'].get").exists())
			.andExpect(jsonPath("$.paths['/api/materials/{materialId}'].delete").exists())
			.andExpect(jsonPath("$.paths['/api/materials/{materialId}/file'].get").exists())
			.andExpect(jsonPath(
				"$.paths['/api/materials/{materialId}/pages/{pageNumber}']"
			).doesNotExist());

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
