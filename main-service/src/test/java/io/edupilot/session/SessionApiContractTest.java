package io.edupilot.session;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.dto.MessageListResponse;
import io.edupilot.session.dto.MessageResponse;
import io.edupilot.session.dto.PendingDiagnosisResponse;
import io.edupilot.session.dto.SessionDetailResponse;
import io.edupilot.session.dto.TurnResponse;
import io.edupilot.session.dto.TurnStateResponse;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class SessionApiContractTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private SessionService sessionService;

	@MockitoBean
	private SessionTurnService turnService;

	@MockitoBean
	private SessionMessageService messageService;

	@MockitoBean
	private SessionStreamService streamService;

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
	private NoteRepository noteRepository;

	@MockitoBean
	private QuizRepository quizRepository;

	@MockitoBean
	private QuizSubmissionRepository quizSubmissionRepository;

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
	void sessionEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(post("/api/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"materialId\":10}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code")
				.value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void detailUsesRestoreContractWithoutInternalSummaries() throws Exception {
		when(sessionService.detail(1L, 100L)).thenReturn(
			new SessionDetailResponse(
				100L,
				10L,
				3,
				PageStatus.EXPLAINED,
				SessionStatus.ACTIVE,
				null,
				null,
				List.of(UiAction.pageExplanation()),
				NOW
			)
		);

		mockMvc.perform(get("/api/sessions/100")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sessionId").value(100))
			.andExpect(jsonPath("$.data.pendingDiagnosis")
				.value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.uiActions[0].type")
				.value("BINARY_DECISION"))
			.andExpect(jsonPath("$.data.conversationSummary").doesNotExist())
			.andExpect(jsonPath("$.data.learnerMemoryDigest").doesNotExist());
	}

	@Test
	void streamRequiresBearerAndReturnsSseHeaders() throws Exception {
		mockMvc.perform(get("/api/sessions/100/stream")
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isUnauthorized());

		when(streamService.connect(1L, 100L))
			.thenReturn(new SseEmitter(0L));
		mockMvc.perform(get("/api/sessions/100/stream")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isOk())
			.andExpect(request().asyncStarted())
			.andExpect(header().string(
				HttpHeaders.CONTENT_TYPE,
				org.hamcrest.Matchers.startsWith(
					MediaType.TEXT_EVENT_STREAM_VALUE
				)
			))
			.andExpect(header().string("X-Accel-Buffering", "no"))
			.andExpect(header().string(
				HttpHeaders.CACHE_CONTROL,
				"no-cache"
			));
	}

	@Test
	void detailRestoresPendingDiagnosisReferenceAndPrompt() throws Exception {
		when(sessionService.detail(1L, 100L)).thenReturn(
			new SessionDetailResponse(
				100L,
				10L,
				3,
				PageStatus.DIAGNOSIS_PENDING,
				SessionStatus.ACTIVE,
				new PendingDiagnosisResponse(30L, "진단 질문"),
				null,
				List.of(UiAction.diagnosisQuestion("진단 질문", 30L)),
				NOW
			)
		);

		mockMvc.perform(get("/api/sessions/100")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.pendingDiagnosis.diagnosisId")
				.value(30))
			.andExpect(jsonPath("$.data.pendingDiagnosis.prompt")
				.value("진단 질문"));
	}

	@Test
	void diagnosisTurnUsesStableNotFoundAndNotPendingErrors()
		throws Exception {
		when(turnService.execute(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(100L),
			org.mockito.ArgumentMatchers.any()
		)).thenThrow(
			new BusinessException(ErrorCode.DIAGNOSIS_NOT_FOUND),
			new BusinessException(ErrorCode.DIAGNOSIS_NOT_PENDING)
		);
		String body = """
			{
			  "requestId": "diagnosis-1",
			  "eventType": "DIAGNOSIS_ANSWER_SUBMITTED",
			  "payload": {"diagnosisId": 30, "answer": "답변"}
			}
			""";

		mockMvc.perform(post("/api/sessions/100/turns")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code")
				.value("DIAGNOSIS_NOT_FOUND"));

		mockMvc.perform(post("/api/sessions/100/turns")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code")
				.value("DIAGNOSIS_NOT_PENDING"));
	}

	@Test
	void turnAndMessagesUseDocumentedEnvelopeAndErrors() throws Exception {
		MessageResponse message = new MessageResponse(
			501L,
			SenderType.AI,
			MessageType.QA,
			"질문에 대한 답변",
			3,
			NOW
		);
		when(turnService.execute(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(100L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(new TurnResponse(
			"turn-123",
			100L,
			List.of(message),
			List.of(),
			new TurnStateResponse(3, PageStatus.EXPLAINED, null)
		));

		mockMvc.perform(post("/api/sessions/100/turns")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "requestId": "request-1",
					  "eventType": "QUIZ_TYPE_SELECTED",
					  "payload": {"quizType": "MCQ"}
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.turnId").value("turn-123"))
			.andExpect(jsonPath("$.data.messages[0].requestId").doesNotExist())
			.andExpect(jsonPath("$.data.messages[0].messageType").value("QA"))
			.andExpect(jsonPath("$.data.state.activeQuizId")
				.value(org.hamcrest.Matchers.nullValue()));

		doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED))
			.when(messageService).messages(1L, 100L, "bad", 30);
		mockMvc.perform(get("/api/sessions/100/messages")
				.param("cursor", "bad")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
