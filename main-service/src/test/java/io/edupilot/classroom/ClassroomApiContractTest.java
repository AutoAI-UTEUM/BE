package io.edupilot.classroom;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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
import io.edupilot.classroom.dto.ClassroomDetailResponse;
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
	void patchPreservesExplicitNullInformation() throws Exception {
		when(classroomService.update(
			eq(1L), eq(UserRole.INSTRUCTOR), eq(30L), any()
		)).thenReturn(detailResponse());

		mockMvc.perform(patch("/api/classrooms/30")
				.header(HttpHeaders.AUTHORIZATION, bearer(instructorToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"description\":null}"))
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

	private String token(Long id, UserRole role) {
		User user = User.create(role.name().toLowerCase() + "@example.com", "hash", role.name(), role);
		ReflectionTestUtils.setField(user, "id", id);
		return jwtTokenProvider.createAccessToken(user);
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}
}
