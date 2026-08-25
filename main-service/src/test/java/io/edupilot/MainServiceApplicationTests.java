package io.edupilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
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
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.config.ReadinessResponse;
import io.edupilot.global.config.ReadinessService;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.material.MaterialOverviewRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizProperties;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.UserRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
@Epic10ServiceMocks
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
	private MaterialOverviewRepository materialOverviewRepository;

	@MockitoBean
	private LearningSessionRepository learningSessionRepository;

	@MockitoBean
	private ChatMessageRepository chatMessageRepository;

	@MockitoBean
	private NoteRepository noteRepository;

	@MockitoBean
	private FeedbackRepository feedbackRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository quizSubmissionRepository;

	@MockitoBean
	private ReadinessService readinessService;

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
	void readinessIsPublicAndUsesTheDedicatedStatusContract() throws Exception {
		when(readinessService.check()).thenReturn(
			ReadinessResponse.of(true, true),
			ReadinessResponse.of(true, false),
			ReadinessResponse.of(false, false)
		);

		mockMvc.perform(get("/api/health/ready")
				.header(TraceIdFilter.TRACE_ID_HEADER, "readiness-trace"))
			.andExpect(status().isOk())
			.andExpect(header().string(
				TraceIdFilter.TRACE_ID_HEADER,
				"readiness-trace"
			))
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.checks.db").value("UP"))
			.andExpect(jsonPath("$.checks.aiService").value("UP"))
			.andExpect(jsonPath("$.success").doesNotExist());

		mockMvc.perform(get("/api/health/ready"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DEGRADED"))
			.andExpect(jsonPath("$.checks.db").value("UP"))
			.andExpect(jsonPath("$.checks.aiService").value("DOWN"));

		mockMvc.perform(get("/api/health/ready"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"))
			.andExpect(jsonPath("$.checks.db").value("DOWN"))
			.andExpect(jsonPath("$.checks.aiService").value("DOWN"));
	}

	@Test
	void quizAndAiPipelineDefaultsMatchAcceptedContracts() {
		assertThat(quizProperties.passRatio())
			.isEqualByComparingTo(new BigDecimal("0.6"));
		assertThat(quizProperties.proposalMinPageTextLength())
			.isEqualTo(200);
		assertThat(aiClientProperties.gradeReadTimeout())
			.isEqualTo(Duration.ofSeconds(110));
		assertThat(aiClientProperties.pipelineReadTimeout())
			.isEqualTo(Duration.ofSeconds(45));
		assertThat(aiClientProperties.assessmentReadTimeout())
			.isEqualTo(Duration.ofSeconds(55));
		assertThat(aiClientProperties.diagnosisReadTimeout())
			.isEqualTo(Duration.ofSeconds(55));
		assertThat(aiClientProperties.reportReadTimeout())
			.isEqualTo(Duration.ofSeconds(220));
		assertThat(aiClientProperties.reportQueryReadTimeout())
			.isEqualTo(Duration.ofSeconds(75));
		assertThat(aiClientProperties.criteriaReadTimeout())
			.isEqualTo(Duration.ofSeconds(90));
		assertThat(aiClientProperties.outlineTimeout())
			.isEqualTo(Duration.ofSeconds(110));
		assertThat(aiClientProperties.turnReadTimeout())
			.isEqualTo(Duration.ofSeconds(200));
		assertThat(aiClientProperties.streamIdleTimeout())
			.isEqualTo(Duration.ofSeconds(30));
		assertThat(aiClientProperties.healthTimeout())
			.isEqualTo(Duration.ofSeconds(2));
		assertThat(aiClientProperties.examDraftReadTimeout())
			.isEqualTo(Duration.ofSeconds(120));
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
			.andExpect(jsonPath(
				"$.paths['/api/auth/email-availability'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/auth/email-availability'].get.parameters[0].name"
			).value("email"))
			.andExpect(jsonPath(
				"$.paths['/api/auth/email-availability'].get.parameters[0].required"
			).value(true))
			.andExpect(jsonPath("$.components.schemas.SignupRequest.required")
				.value(org.hamcrest.Matchers.hasItem("role")))
			.andExpect(jsonPath(
				"$.components.schemas.SignupRequest.properties.role.enum"
			)
				.value(org.hamcrest.Matchers.contains("LEARNER", "INSTRUCTOR")))
			.andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
			.andExpect(jsonPath("$.paths['/api/auth/refresh'].post").exists())
			.andExpect(jsonPath("$.paths['/api/auth/logout'].post").exists())
			.andExpect(jsonPath("$.paths['/api/users/me'].get").exists())
			.andExpect(jsonPath("$.paths['/api/users/me'].patch").exists())
			.andExpect(jsonPath("$.paths['/api/users/me/avatar'].post").exists())
			.andExpect(jsonPath("$.paths['/api/users/me/avatar'].get").exists())
			.andExpect(jsonPath("$.paths['/api/users/me/avatar'].delete").exists())
			.andExpect(jsonPath(
				"$.paths['/api/users/me/avatar'].post.requestBody.content"
					+ "['multipart/form-data'].schema.properties.file"
			).exists())
			.andExpect(jsonPath("$.paths['/api/users/me/preferences'].get").exists())
			.andExpect(jsonPath("$.paths['/api/users/me/preferences'].patch").exists())
			.andExpect(jsonPath(
				"$.components.schemas.UpdatePreferencesRequest.properties"
					+ ".aiAnswerStyle.enum"
			).value(org.hamcrest.Matchers.contains(
				"CONCISE",
				"NORMAL",
				"DETAILED"
			)))
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
				"$.paths['/api/sessions/{sessionId}/notes'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/notes'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/materials/{materialId}/notes'].get"
			).exists())
			.andExpect(jsonPath("$.paths['/api/notes/{noteId}'].patch").exists())
			.andExpect(jsonPath("$.paths['/api/notes/{noteId}'].delete").exists())
			.andExpect(jsonPath(
				"$.components.schemas.CreateNoteRequest.required"
			).value(org.hamcrest.Matchers.hasItem("content")))
			.andExpect(jsonPath("$.paths['/api/feedback'].post").exists())
			.andExpect(jsonPath("$.paths['/api/feedback'].get").doesNotExist())
			.andExpect(jsonPath("$.paths['/api/classrooms'].post").exists())
			.andExpect(jsonPath("$.paths['/api/classrooms'].get").exists())
			.andExpect(jsonPath("$.paths['/api/classrooms/{id}'].get").exists())
			.andExpect(jsonPath("$.paths['/api/classrooms/{id}'].patch").exists())
			.andExpect(jsonPath("$.paths['/api/classrooms/{id}'].delete").exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/analytics'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{classroomId}/students/{studentId}/learning-analytics'].get.operationId"
			).value("getClassroomStudentLearningAnalytics"))
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{classroomId}/students/{studentId}/learning-analytics'].get.parameters[2].name"
			).value("questionPeriod"))
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{classroomId}/students/{studentId}/learning-analytics'].get.parameters[2].schema.enum"
			).value(org.hamcrest.Matchers.containsInAnyOrder(
				"LAST_7_DAYS",
				"ALL"
			)))
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomAnalyticsResponse.properties.questionsByPage"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomSummaryResponse.properties.materialCount"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomWeekResponse.properties.averageProgressRate"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomWeekMaterialResponse.properties.viewerCount"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomStudentResponse.properties.averageProgressRate"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomStudentResponse.properties.aiQuestionCountLast7Days"
			).exists())
			.andExpect(jsonPath("$.paths['/api/classroom-join-requests'].post").exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks/{weekNumber}'].patch"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks/{weekNumber}'].delete"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks/{weekId}/status'].patch"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks/reorder'].patch"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.UpdateClassroomWeekStatusRequest"
					+ ".properties.status.enum"
			).value(org.hamcrest.Matchers.contains(
				"PRIVATE",
				"SCHEDULED",
				"PUBLISHED",
				"BREAK"
			)))
			.andExpect(jsonPath(
				"$.components.schemas.ReorderClassroomWeeksRequest.required"
			).value(org.hamcrest.Matchers.hasItem("orderedWeekIds")))
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomWeekResponse.properties.weekId"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.ClassroomWeekResponse.properties.displayOrder"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/weeks/{weekNumber}/materials/{materialId}'].delete"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/notices'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/notices'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/notices/{noticeId}'].patch"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/notices/{noticeId}'].delete"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/users/me/schedule'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/users/me/schedule'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/users/me/schedule/{scheduleId}'].patch"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/users/me/schedule/{scheduleId}'].delete"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.CreatePersonalScheduleRequest.required"
			).value(org.hamcrest.Matchers.hasItems(
				"title", "startsAt", "endsAt", "hasTime"
			)))
			.andExpect(jsonPath(
				"$.components.schemas.CreateFeedbackRequest.required"
			).value(org.hamcrest.Matchers.hasItems("category", "message")))
			.andExpect(jsonPath(
				"$.components.schemas.CreateFeedbackRequest.properties.category.enum"
			).value(org.hamcrest.Matchers.contains(
				"BUG",
				"FEATURE_REQUEST",
				"GENERAL"
			)))
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
				"$.paths['/api/classrooms/{classroomId}/exams'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{classroomId}/exams'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{classroomId}/exams/{examId}/draft-questions'].post"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.Question.discriminator.propertyName"
			).value("questionType"))
			.andExpect(jsonPath("$.paths['/api/exams/{examId}'].get").exists())
			.andExpect(jsonPath("$.paths['/api/exams/{examId}'].patch").exists())
			.andExpect(jsonPath("$.paths['/api/exams/{examId}'].delete").exists())
			.andExpect(jsonPath(
				"$.paths['/api/exams/{examId}/publish'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/exams/{examId}/close'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/exams/{examId}/submissions'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/exams/{examId}/submissions'].post"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/exams/{examId}/submissions/{submissionId}'].get"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/exams/{examId}/submissions/me'].get"
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
			.andExpect(jsonPath(
				"$.paths['/api/materials'].post.requestBody.content"
					+ "['multipart/form-data'].schema.properties.classroomId"
			).exists())
			.andExpect(jsonPath(
				"$.paths['/api/materials'].post.requestBody.content"
					+ "['multipart/form-data'].schema.properties.weekNumber"
			).exists())
			.andExpect(jsonPath("$.paths['/api/materials'].get").exists())
			.andExpect(jsonPath("$.paths['/api/materials/{materialId}'].get").exists())
			.andExpect(jsonPath(
				"$.components.schemas.MaterialSummaryResponse.properties.failureReason"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.MaterialSummaryResponse.properties.traceId.maxLength"
			).value(64))
			.andExpect(jsonPath(
				"$.components.schemas.MaterialDetailResponse.properties.failureReason"
			).exists())
			.andExpect(jsonPath(
				"$.components.schemas.MaterialDetailResponse.properties.traceId.maxLength"
			).value(64))
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
					"Authorization, Content-Type, X-Trace-Id"
				))
			.andExpect(status().isOk())
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
				"http://localhost:5173"
			))
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
				"true"
			))
			.andExpect(header().string(
				HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
				TraceIdFilter.TRACE_ID_HEADER
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
