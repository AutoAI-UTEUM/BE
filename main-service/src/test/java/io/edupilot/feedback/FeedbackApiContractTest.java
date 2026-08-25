package io.edupilot.feedback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.feedback.dto.FeedbackResponse;
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

@SpringBootTest
@ActiveProfiles("test")
@io.edupilot.Epic10ServiceMocks
class FeedbackApiContractTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private FeedbackService feedbackService;

	@MockitoBean
	private FeedbackRepository feedbackRepository;

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
		User user = User.create("user@example.com", "hash", "학습자");
		ReflectionTestUtils.setField(user, "id", 1L);
		accessToken = jwtTokenProvider.createAccessToken(user);
	}

	@Test
	void createRequiresAuthenticationAndReturnsReceipt() throws Exception {
		String body = """
			{
			  "category": "BUG",
			  "message": "채팅 화면이 멈춥니다.",
			  "pageUrl": "https://app.example/sessions/10",
			  "clientVersion": "web-1.2.3"
			}
			""";

		mockMvc.perform(post("/api/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code")
				.value("AUTHENTICATION_REQUIRED"));

		when(feedbackService.create(eq(1L), any()))
			.thenReturn(new FeedbackResponse(100L, NOW));

		mockMvc.perform(post("/api/feedback")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.feedbackId").value(100))
			.andExpect(jsonPath("$.data.createdAt")
				.value("2026-08-02T00:00:00Z"));
	}

	@Test
	void createRejectsUnknownCategoryAndOversizedMessage() throws Exception {
		mockMvc.perform(post("/api/feedback")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"category\":\"OTHER\",\"message\":\"내용\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));

		String oversized = "a".repeat(2001);
		mockMvc.perform(post("/api/feedback")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"category\":\"GENERAL\",\"message\":\""
					+ oversized + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
