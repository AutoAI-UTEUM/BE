package io.edupilot.report;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.Epic10ServiceMocks;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.classroom.ClassroomStudentService;
import io.edupilot.classroom.dto.ClassroomStudentListResponse;
import io.edupilot.classroom.dto.ClassroomStudentResponse;
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.report.dto.ReportAcceptedResponse;
import io.edupilot.report.dto.ReportCompletedResponse;
import io.edupilot.report.dto.ReportFailedResponse;
import io.edupilot.report.dto.ReportProgressResponse;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest
@ActiveProfiles("test")
@Epic10ServiceMocks
class ReportApiContractTest {

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private ReportApiService reportApiService;
	@Autowired private ReportCriterionService reportCriterionService;
	@Autowired private ClassroomStudentService classroomStudentService;

	@MockitoBean private UserRepository userRepository;
	@MockitoBean private RefreshTokenRepository refreshTokenRepository;
	@MockitoBean private LearningMaterialRepository materialRepository;
	@MockitoBean private MaterialPageRepository materialPageRepository;
	@MockitoBean private LearningSessionRepository sessionRepository;
	@MockitoBean private ChatMessageRepository messageRepository;
	@MockitoBean private NoteRepository noteRepository;
	@MockitoBean private FeedbackRepository feedbackRepository;
	@MockitoBean private QuizRepository quizRepository;
	@MockitoBean private QuizSubmissionRepository submissionRepository;

	private MockMvc mockMvc;
	private String instructorToken;
	private String learnerToken;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
		instructorToken = token(1L, UserRole.INSTRUCTOR);
		learnerToken = token(2L, UserRole.LEARNER);
	}

	@Test
	void createAlwaysReturns202WithStableStringIdAndPollingDelay() throws Exception {
		when(reportApiService.create(
			eq(1L), eq(UserRole.INSTRUCTOR), eq(30L), eq(40L), any(), any()
		)).thenReturn(new ReportAcceptedResponse(
			"901", ReportGenerationStatus.PENDING, 5
		));

		for (String requestId : List.of("first", "first", "active-duplicate")) {
			mockMvc.perform(post("/api/classrooms/30/students/40/reports")
					.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
						{"requestId":"%s","scope":"FULL"}
						""".formatted(requestId)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.reportId").value("901"))
				.andExpect(jsonPath("$.data.status").value("PENDING"))
				.andExpect(jsonPath("$.data.pollAfterSeconds").value(5));
		}
	}

	@Test
	void weekScopeRequiresWeekNumber() throws Exception {
		mockMvc.perform(post("/api/classrooms/30/students/40/reports")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"requestId":"week-1","scope":"WEEK"}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void detailSerializesEachStateWithoutInternalFields() throws Exception {
		ReportProgressResponse processing = new ReportProgressResponse(
			"901", ReportGenerationStatus.PROCESSING, 5
		);
		ReportCompletedResponse completed = new ReportCompletedResponse(
			"901",
			ReportGenerationStatus.COMPLETED,
			2,
			1,
			new BigDecimal("70.00"),
			"GOOD",
			java.util.Map.of("overview", "요약"),
			List.of(
				new ReportCompletedResponse.CriterionResult(
					"sparse", 1, null, null,
					ReportCriterionStatus.INSUFFICIENT_DATA,
					"근거 부족", List.of()
				),
				new ReportCompletedResponse.CriterionResult(
					"zero", 1, BigDecimal.ZERO, ReportTrend.FLAT,
					ReportCriterionStatus.ASSESSED,
					"평가", List.of("ev-1")
				)
			),
			List.of(new ReportCompletedResponse.Evidence(
				"ev-1", "QUIZ_SUBMISSION", "퀴즈 제출", Instant.EPOCH
			)),
			Instant.EPOCH
		);
		ReportFailedResponse failed = new ReportFailedResponse(
			"901",
			ReportGenerationStatus.FAILED,
			"AI_SERVICE_TIMEOUT",
			new ReportFailedResponse.Fallback(
				java.util.Map.of("sessionCount", 2),
				java.util.Map.of("progressDataAvailable", true)
			)
		);
		when(reportApiService.detail(1L, UserRole.INSTRUCTOR, "901"))
			.thenReturn(processing, completed, failed);

		mockMvc.perform(get("/api/reports/901")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").value("901"))
			.andExpect(jsonPath("$.data.status").value("PROCESSING"))
			.andExpect(jsonPath("$.data.pollAfterSeconds").value(5));

		mockMvc.perform(get("/api/reports/901")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").value("901"))
			.andExpect(jsonPath("$.data.criteria[0].score").value(nullValue()))
			.andExpect(jsonPath("$.data.criteria[0].status")
				.value("INSUFFICIENT_DATA"))
			.andExpect(jsonPath("$.data.criteria[1].score").value(0))
			.andExpect(jsonPath("$.data.criteria[1].trend").value("FLAT"))
			.andExpect(jsonPath("$.data.overallScore").value(70.0))
			.andExpect(jsonPath("$.data.overallStage").value("GOOD"))
			.andExpect(content().string(not(containsString("sourceRef"))))
			.andExpect(content().string(not(containsString("snapshotHash"))))
			.andExpect(content().string(not(containsString("generationLeaseToken"))));

		mockMvc.perform(get("/api/reports/901")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.reportId").value("901"))
			.andExpect(jsonPath("$.data.status").value("FAILED"))
			.andExpect(jsonPath("$.data.failureCode").value("AI_SERVICE_TIMEOUT"))
			.andExpect(jsonPath("$.data.fallback.metrics.sessionCount").value(2))
			.andExpect(jsonPath("$.data.criteria").doesNotExist());
	}

	@Test
	void detailHidesReportsFromOtherInstructorsAndRejectsLearners() throws Exception {
		doThrow(new BusinessException(ErrorCode.REPORT_NOT_FOUND))
			.when(reportApiService)
			.detail(1L, UserRole.INSTRUCTOR, "999");
		doThrow(new BusinessException(ErrorCode.ACCESS_DENIED))
			.when(reportApiService)
			.detail(2L, UserRole.LEARNER, "901");

		mockMvc.perform(get("/api/reports/999")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("REPORT_NOT_FOUND"));
		mockMvc.perform(get("/api/reports/901")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void criterionLimitAndDuplicateFailuresUseConflictCodes() throws Exception {
		doThrow(new BusinessException(ErrorCode.REPORT_CRITERION_LIMIT_EXCEEDED))
			.when(reportCriterionService)
			.create(eq(1L), eq(UserRole.INSTRUCTOR), eq(30L), any());

		mockMvc.perform(post("/api/classrooms/30/report-criteria")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "criterionKey":"custom",
					  "name":"추가 기준",
					  "description":"설명",
					  "rubric":{"summary":"평가"},
					  "allowedSources":["SESSION"],
					  "minEvidence":2,
					  "weight":1
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code")
				.value("REPORT_CRITERION_LIMIT_EXCEEDED"));
	}

	@Test
	void studentsExposeManagementFieldsAndRemovalEndpoint() throws Exception {
		when(classroomStudentService.list(
			1L, UserRole.INSTRUCTOR, 30L, 0, 20
		)).thenReturn(new ClassroomStudentListResponse(
			List.of(new ClassroomStudentResponse(
				40L,
				"학생",
				"student@example.com",
				null,
				Instant.EPOCH,
				"ACTIVE",
				null
			)),
			0,
			20,
			1,
			1
		));

		mockMvc.perform(get("/api/classrooms/30/students")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].studentId").value(40))
			.andExpect(jsonPath("$.data.items[0].name").value("학생"))
			.andExpect(jsonPath("$.data.items[0].email")
				.value("student@example.com"))
			.andExpect(jsonPath("$.data.items[0].affiliation").value(nullValue()))
			.andExpect(jsonPath("$.data.items[0].joinedAt").exists())
			.andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.items[0].lastActiveAt").value(nullValue()));

		mockMvc.perform(delete("/api/classrooms/30/students/40")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk());
		verify(classroomStudentService).remove(
			1L, UserRole.INSTRUCTOR, 30L, 40L
		);
	}

	private String token(Long id, UserRole role) {
		User user = User.create(
			role.name().toLowerCase() + "@example.com", "hash", role.name(), role
		);
		ReflectionTestUtils.setField(user, "id", id);
		return jwtTokenProvider.createAccessToken(user);
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}
}
