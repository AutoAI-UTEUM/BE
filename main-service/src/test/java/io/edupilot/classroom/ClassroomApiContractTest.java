package io.edupilot.classroom;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import io.edupilot.classroom.dto.ClassroomAnalyticsMaterialResponse;
import io.edupilot.classroom.dto.ClassroomAnalyticsResponse;
import io.edupilot.classroom.dto.ClassroomDetailResponse;
import io.edupilot.classroom.dto.ClassroomQuestionByPageResponse;
import io.edupilot.classroom.dto.ClassroomWeekListResponse;
import io.edupilot.classroom.dto.ClassroomWeekResponse;
import io.edupilot.classroom.dto.ReorderClassroomWeeksRequest;
import io.edupilot.classroom.dto.UpdateClassroomWeekStatusRequest;
import io.edupilot.classroom.dto.UpdateClassroomRequest;
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest
@ActiveProfiles("test")
@Epic10ServiceMocks
class ClassroomApiContractTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private ClassroomService classroomService;

	@Autowired
	private ClassroomWeekService classroomWeekService;

	@Autowired
	private ClassroomAnalyticsService analyticsService;

	@MockitoBean
	private UserRepository userRepository;

	@MockitoBean
	private RefreshTokenRepository refreshTokenRepository;

	@MockitoBean
	private LearningMaterialRepository materialRepository;

	@MockitoBean
	private MaterialPageRepository materialPageRepository;

	@MockitoBean
	private LearningSessionRepository sessionRepository;

	@MockitoBean
	private ChatMessageRepository messageRepository;

	@MockitoBean
	private NoteRepository noteRepository;

	@MockitoBean
	private FeedbackRepository feedbackRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository submissionRepository;

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
	void createReturnsDetailAndRejectsInvalidColorAndNonInstructor() throws Exception {
		when(classroomService.create(eq(1L), eq(UserRole.INSTRUCTOR), any()))
			.thenReturn(detailResponse());
		mockMvc.perform(post("/api/classrooms")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "AI 기초",
					  "startDate": "2026-09-01",
					  "endDate": "2026-12-15",
					  "color": "BLUE"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.classroomId").value(30))
			.andExpect(jsonPath("$.data.weekCount").value(16))
			.andExpect(jsonPath("$.data.inviteCode").value("7KMX-9QTR"));
		mockMvc.perform(post("/api/classrooms")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "AI 기초",
					  "startDate": "2026-09-01",
					  "endDate": "2026-12-15",
					  "color": "CYAN"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		doThrow(new BusinessException(ErrorCode.ACCESS_DENIED))
			.when(classroomService)
			.create(eq(2L), eq(UserRole.LEARNER), any());
		mockMvc.perform(post("/api/classrooms")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "AI 기초",
					  "startDate": "2026-09-01",
					  "endDate": "2026-12-15",
					  "color": "BLUE"
					}
					"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void patchPreservesPresentFieldsForDatesShiftAndExplicitNull() throws Exception {
		when(classroomService.update(
			eq(1L), eq(UserRole.INSTRUCTOR), eq(30L), any()
		)).thenReturn(detailResponse());
		mockMvc.perform(patch("/api/classrooms/30")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "startDate": "2026-09-08",
					  "endDate": "2026-12-22",
					  "shiftWeekReleaseDates": true,
					  "description": null
					}
					"""))
			.andExpect(status().isOk());
		ArgumentCaptor<UpdateClassroomRequest> captor =
			ArgumentCaptor.forClass(UpdateClassroomRequest.class);
		verify(classroomService).update(
			eq(1L), eq(UserRole.INSTRUCTOR), eq(30L), captor.capture()
		);
		org.assertj.core.api.Assertions.assertThat(captor.getValue().isDescriptionPresent())
			.isTrue();
		org.assertj.core.api.Assertions.assertThat(captor.getValue().getDescription())
			.isNull();
		org.assertj.core.api.Assertions.assertThat(captor.getValue().isStartDatePresent())
			.isTrue();
		org.assertj.core.api.Assertions.assertThat(captor.getValue().getStartDate())
			.isEqualTo(LocalDate.of(2026, 9, 8));
		org.assertj.core.api.Assertions.assertThat(captor.getValue().isEndDatePresent())
			.isTrue();
		org.assertj.core.api.Assertions.assertThat(captor.getValue().getEndDate())
			.isEqualTo(LocalDate.of(2026, 12, 22));
		org.assertj.core.api.Assertions.assertThat(
			captor.getValue().isShiftWeekReleaseDatesPresent()
		).isTrue();
		org.assertj.core.api.Assertions.assertThat(
			captor.getValue().getShiftWeekReleaseDates()
		).isTrue();
	}

	@Test
	void permanentDeleteUsesConfirmNameAndStableErrorEnvelope() throws Exception {
		mockMvc.perform(delete("/api/classrooms/30/permanent")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirmName\":\"AI 기초\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));
		verify(classroomService).deletePermanently(
			1L,
			UserRole.INSTRUCTOR,
			30L,
			new io.edupilot.classroom.dto.PermanentDeleteClassroomRequest("AI 기초")
		);

		doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED))
			.when(classroomService)
			.deletePermanently(eq(1L), eq(UserRole.INSTRUCTOR), eq(30L), any());
		mockMvc.perform(delete("/api/classrooms/30/permanent")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirmName\":\"AI  기초\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

		doThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND))
			.when(classroomService)
			.deletePermanently(eq(2L), eq(UserRole.LEARNER), eq(30L), any());
		mockMvc.perform(delete("/api/classrooms/30/permanent")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"confirmName\":\"AI 기초\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("CLASSROOM_NOT_FOUND"));
	}

	@Test
	void openApiDocumentsPermanentDeleteRequest() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(
				"$.paths['/api/classrooms/{id}/permanent'].delete.summary"
			).value("강의실 영구 삭제"))
			.andExpect(jsonPath(
				"$.components.schemas.PermanentDeleteClassroomRequest.required[0]"
			).value("confirmName"));
	}

	@Test
	void openApiDocumentsClassroomDateAndWeekReleaseShiftFields() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(
				"$.components.schemas.UpdateClassroomRequest.properties.startDate.format"
			).value("date"))
			.andExpect(jsonPath(
				"$.components.schemas.UpdateClassroomRequest.properties.endDate.format"
			).value("date"))
			.andExpect(jsonPath(
				"$.components.schemas.UpdateClassroomRequest.properties.shiftWeekReleaseDates.type"
			).value("boolean"));
	}

	@Test
	void weekStatusAndReorderContractsExposeStableIdsAndOrder() throws Exception {
		ClassroomWeekResponse privateWeek = weekResponse(
			10L,
			1,
			ClassroomWeekStatus.PRIVATE,
			2
		);
		when(classroomWeekService.changeStatus(
			eq(1L),
			eq(UserRole.INSTRUCTOR),
			eq(30L),
			eq(10L),
			any()
		)).thenReturn(privateWeek);
		mockMvc.perform(patch("/api/classrooms/30/weeks/10/status")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"PRIVATE\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.weekId").value(10))
			.andExpect(jsonPath("$.data.weekNumber").value(1))
			.andExpect(jsonPath("$.data.status").value("PRIVATE"))
			.andExpect(jsonPath("$.data.displayOrder").value(2));
		ClassroomWeekListResponse reordered = new ClassroomWeekListResponse(List.of(
			weekResponse(20L, 2, ClassroomWeekStatus.PUBLISHED, 1),
			weekResponse(10L, 1, ClassroomWeekStatus.PRIVATE, 2)
		));
		when(classroomWeekService.reorder(
			eq(1L),
			eq(UserRole.INSTRUCTOR),
			eq(30L),
			any()
		)).thenReturn(reordered);
		mockMvc.perform(patch("/api/classrooms/30/weeks/reorder")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderedWeekIds\":[20,10]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].weekId").value(20))
			.andExpect(jsonPath("$.data.items[0].displayOrder").value(1))
			.andExpect(jsonPath("$.data.items[1].weekId").value(10))
			.andExpect(jsonPath("$.data.items[1].displayOrder").value(2));
		ArgumentCaptor<UpdateClassroomWeekStatusRequest> statusCaptor =
			ArgumentCaptor.forClass(UpdateClassroomWeekStatusRequest.class);
		verify(classroomWeekService).changeStatus(
			eq(1L),
			eq(UserRole.INSTRUCTOR),
			eq(30L),
			eq(10L),
			statusCaptor.capture()
		);
		org.assertj.core.api.Assertions.assertThat(statusCaptor.getValue().status())
			.isEqualTo(ClassroomWeekStatus.PRIVATE);
		ArgumentCaptor<ReorderClassroomWeeksRequest> reorderCaptor =
			ArgumentCaptor.forClass(ReorderClassroomWeeksRequest.class);
		verify(classroomWeekService).reorder(
			eq(1L),
			eq(UserRole.INSTRUCTOR),
			eq(30L),
			reorderCaptor.capture()
		);
		org.assertj.core.api.Assertions.assertThat(
			reorderCaptor.getValue().orderedWeekIds()
		).containsExactly(20L, 10L);
	}

	@Test
	void weekStatusAndReorderRejectMalformedBodies() throws Exception {
		mockMvc.perform(patch("/api/classrooms/30/weeks/10/status")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"OPEN\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
		mockMvc.perform(patch("/api/classrooms/30/weeks/reorder")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"orderedWeekIds\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void analyticsReturnsInstructorDashboardContractAndHidesOtherOwner()
		throws Exception {
		Instant updatedAt = Instant.parse("2026-08-04T03:00:00Z");
		when(analyticsService.getAnalytics(1L, UserRole.INSTRUCTOR, 30L))
			.thenReturn(new ClassroomAnalyticsResponse(
				2L,
				38,
				4L,
				1L,
				updatedAt,
				List.of(new ClassroomAnalyticsMaterialResponse(
					10L, "AI Basics", 1L, 50, 25
				)),
				List.of(new ClassroomQuestionByPageResponse(10L, 2, 4L))
			));
		mockMvc.perform(get("/api/classrooms/30/analytics")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.learnerCount").value(2))
			.andExpect(jsonPath("$.data.averageProgressRate").value(38))
			.andExpect(jsonPath("$.data.aiQuestionCountLast7Days").value(4))
			.andExpect(jsonPath("$.data.materials[0].viewRate").value(50))
			.andExpect(jsonPath("$.data.questionsByPage[0].pageNumber").value(2))
			.andExpect(jsonPath("$.data.lastUpdatedAt").value(updatedAt.toString()));
		doThrow(new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND))
			.when(analyticsService)
			.getAnalytics(2L, UserRole.LEARNER, 30L);
		mockMvc.perform(get("/api/classrooms/30/analytics")
				.header(HttpHeaders.AUTHORIZATION, bearer(learnerToken)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("CLASSROOM_NOT_FOUND"));
	}

	private ClassroomDetailResponse detailResponse() {
		return new ClassroomDetailResponse(
			30L,
			"AI 기초",
			"홍강사",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			16,
			ClassroomColor.BLUE,
			null,
			ClassroomStatus.ACTIVE,
			1,
			0,
			null,
			null,
			0L,
			"7KMX-9QTR"
		);
	}

	private ClassroomWeekResponse weekResponse(
		Long weekId,
		int weekNumber,
		ClassroomWeekStatus status,
		int displayOrder
	) {
		return new ClassroomWeekResponse(
			weekId,
			weekNumber,
			"Week " + weekNumber,
			status,
			displayOrder,
			null,
			0,
			List.of()
		);
	}

	private String token(Long id, UserRole role) {
		User user = User.create(role.name().toLowerCase() + "@example.com", "hash", role.name(), role);
		ReflectionTestUtils.setField(user, "id", id);
		return jwtTokenProvider.createAccessToken(user);
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}
}
