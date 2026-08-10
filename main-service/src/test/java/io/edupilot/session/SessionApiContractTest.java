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
import io.edupilot.feedback.FeedbackRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.note.NoteRepository;
import io.edupilot.quiz.QuizRepository;
import io.edupilot.quiz.QuizSubmissionRepository;
import io.edupilot.session.dto.ConversationStartResponse;
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
@io.edupilot.Epic10ServiceMocks
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
	private FeedbackRepository feedbackRepository;

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
			ChatMessageStatus.COMPLETED,
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
			.andExpect(jsonPath("$.data.messages[0].status")
				.value("COMPLETED"))
			.andExpect(jsonPath("$.data.state.activeQuizId")
				.value(org.hamcrest.Matchers.nullValue()));

		MessageResponse failedMessage = new MessageResponse(
			500L,
			SenderType.USER,
			MessageType.TEXT,
			"실패한 질문",
			3,
			ChatMessageStatus.FAILED,
			NOW
		);
		when(messageService.messages(1L, 100L, null, 30))
			.thenReturn(new MessageListResponse(
				List.of(failedMessage),
				null,
				false
			));
		mockMvc.perform(get("/api/sessions/100/messages")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.items[0].status")
				.value("FAILED"));

		doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED))
			.when(messageService).messages(1L, 100L, "bad", 30);
		mockMvc.perform(get("/api/sessions/100/messages")
				.param("cursor", "bad")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void newConversationUsesAuthenticatedBodylessContractAndConflictError()
		throws Exception {
		when(sessionService.startNewConversation(1L, 100L)).thenReturn(
			new ConversationStartResponse("conversation-1", NOW)
		);

		mockMvc.perform(post("/api/sessions/100/conversations")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.conversationId")
				.value("conversation-1"))
			.andExpect(jsonPath("$.data.startedAt")
				.value(NOW.toString()));

		mockMvc.perform(post("/api/sessions/100/conversations"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code")
				.value("AUTHENTICATION_REQUIRED"));

		when(sessionService.startNewConversation(1L, 100L))
			.thenThrow(new BusinessException(ErrorCode.SESSION_STATE_CONFLICT));
		mockMvc.perform(post("/api/sessions/100/conversations")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code")
				.value("SESSION_STATE_CONFLICT"));
	}

	@Test
	void quizDeclineUsesBodylessUiActionArrayContract() throws Exception {
		when(sessionService.declineQuizProposal(1L, 100L))
			.thenReturn(List.of(UiAction.moveNextPage()));

		mockMvc.perform(post("/api/sessions/100/quiz-decline")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].type")
				.value("BINARY_DECISION"))
			.andExpect(jsonPath("$.data[0].yesEvent")
				.value("MOVE_NEXT_PAGE"))
			.andExpect(jsonPath("$.data[0].noEvent").value("WAIT"));

		mockMvc.perform(post("/api/sessions/100/quiz-decline"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code")
				.value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void quizDeclineKeepsHiddenAndInactiveSessionErrors() throws Exception {
		when(sessionService.declineQuizProposal(1L, 100L))
			.thenThrow(
				new BusinessException(ErrorCode.SESSION_NOT_FOUND),
				new BusinessException(ErrorCode.SESSION_NOT_ACTIVE)
			);

		mockMvc.perform(post("/api/sessions/100/quiz-decline")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code")
				.value("SESSION_NOT_FOUND"));

		mockMvc.perform(post("/api/sessions/100/quiz-decline")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code")
				.value("SESSION_NOT_ACTIVE"));
	}

	@Test
	void openApiDocumentsBodylessSessionEndpoints() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/conversations']"
					+ ".post.summary"
			).value("LLM 호출 없는 새 대화 시작"))
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/quiz-decline']"
					+ ".post.summary"
			).value("퀴즈 제안 거절"))
			.andExpect(jsonPath(
				"$.paths['/api/sessions/{sessionId}/quiz-decline']"
					+ ".post.requestBody"
			).doesNotExist());
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
