package io.edupilot.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.schedule.dto.PersonalScheduleResponse;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@Epic10ServiceMocks
class ScheduleApiContractTest {

	private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

	@Autowired
	private WebApplicationContext context;
	@Autowired
	private TraceIdFilter traceIdFilter;
	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private PersonalScheduleService personalScheduleService;
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
	private String accessToken;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
		User user = User.create("user@example.com", "hash", "Learner");
		ReflectionTestUtils.setField(user, "id", 1L);
		accessToken = jwtTokenProvider.createAccessToken(user);
	}

	@Test
	void createsUpdatesAndDeletesPersonalSchedule() throws Exception {
		when(personalScheduleService.create(eq(1L), any())).thenReturn(response());
		when(personalScheduleService.update(eq(1L), eq(10L), any()))
			.thenReturn(response());

		mockMvc.perform(post("/api/users/me/schedule")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "Study plan",
					  "startsAt": "2026-08-03T00:00:00Z",
					  "endsAt": "2026-08-03T00:00:00Z",
					  "hasTime": false
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scheduleId").value("10"))
			.andExpect(jsonPath("$.data.kind").value("PERSONAL"))
			.andExpect(jsonPath("$.data.hasTime").value(false));

		mockMvc.perform(patch("/api/users/me/schedule/10")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Updated\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.scheduleId").value("10"));

		mockMvc.perform(delete("/api/users/me/schedule/10")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk());
	}

	@Test
	void rejectsMalformedCreateRequest() throws Exception {
		mockMvc.perform(post("/api/users/me/schedule")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": " ",
					  "startsAt": "2026-08-03T00:00:00Z",
					  "endsAt": "2026-08-03T01:00:00Z"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void hidesOtherUsersScheduleForUpdateAndDelete() throws Exception {
		doThrow(new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND))
			.when(personalScheduleService).update(eq(1L), eq(20L), any());
		doThrow(new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND))
			.when(personalScheduleService).delete(1L, 20L);

		mockMvc.perform(patch("/api/users/me/schedule/20")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"Hidden\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("SCHEDULE_NOT_FOUND"));

		mockMvc.perform(delete("/api/users/me/schedule/20")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("SCHEDULE_NOT_FOUND"));
	}

	private PersonalScheduleResponse response() {
		return new PersonalScheduleResponse(
			"10", ScheduleType.PERSONAL, "Study plan", START, START, false
		);
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
