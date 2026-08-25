package io.edupilot.notification;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
import io.edupilot.notification.dto.NotificationListResponse;
import io.edupilot.notification.dto.NotificationResponse;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
@Epic10ServiceMocks
class NotificationApiContractTest {

	@Autowired
	private WebApplicationContext context;
	@Autowired
	private TraceIdFilter traceIdFilter;
	@Autowired
	private JwtTokenProvider jwtTokenProvider;
	@Autowired
	private NotificationService notificationService;

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
	void listRequiresAuthenticationAndReturnsPageAndLinkContract() throws Exception {
		mockMvc.perform(get("/api/users/me/notifications"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

		var item = response(null);
		when(notificationService.list(1L, 0, 20)).thenReturn(
			new NotificationListResponse(List.of(item), 0, 20, 1, 1)
		);
		mockMvc.perform(get("/api/users/me/notifications")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].notificationId").value(100))
			.andExpect(jsonPath("$.data.items[0].type").value("NOTICE_PUBLISHED"))
			.andExpect(jsonPath("$.data.items[0].link.classroomId").value(30))
			.andExpect(jsonPath("$.data.items[0].link.noticeId").value(70))
			.andExpect(jsonPath("$.data.items[0].readAt")
				.value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.totalElements").value(1));

		mockMvc.perform(get("/api/users/me/notifications")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.param("size", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void readIsIdempotentDeleteReturnsNullAndOtherUsersAreHidden() throws Exception {
		Instant readAt = Instant.parse("2026-08-14T03:01:00Z");
		when(notificationService.read(1L, 100L)).thenReturn(response(readAt));

		mockMvc.perform(patch("/api/users/me/notifications/100/read")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.readAt").value("2026-08-14T03:01:00Z"));
		mockMvc.perform(delete("/api/users/me/notifications/100")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data").doesNotExist());

		doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
			.when(notificationService).delete(1L, 200L);
		mockMvc.perform(delete("/api/users/me/notifications/200")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
	}

	private NotificationResponse response(Instant readAt) {
		return new NotificationResponse(
			100L,
			NotificationType.NOTICE_PUBLISHED,
			"Notice",
			"Content",
			Map.of("classroomId", 30L, "noticeId", 70L),
			readAt,
			Instant.parse("2026-08-14T03:00:00Z")
		);
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
