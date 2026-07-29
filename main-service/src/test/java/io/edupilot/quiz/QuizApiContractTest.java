package io.edupilot.quiz;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.auth.RefreshTokenRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.quiz.dto.QuizDetailResponse;
import io.edupilot.quiz.dto.QuizListResponse;
import io.edupilot.quiz.dto.QuizGradingResultResponse;
import io.edupilot.quiz.dto.QuizSubmitResponse;
import io.edupilot.quiz.dto.QuizSummaryResponse;
import io.edupilot.quiz.dto.QuizQuestionResponse;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.session.UiAction;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest
@ActiveProfiles("test")
class QuizApiContractTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private TraceIdFilter traceIdFilter;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private QuizService quizService;

	@MockitoBean
	private QuizSubmissionService submissionService;

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
	private ChatMessageRepository chatMessageRepository;

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
	void quizEndpointsRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/quizzes/50"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code")
				.value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void detailAndListExposeOnlyPublicQuizData() throws Exception {
		PublicQuizQuestion question = new PublicQuizQuestion(
			"q1",
			"공개 문항",
			new BigDecimal("20.00"),
			List.of(
				new QuizOption("a", "선택지 A"),
				new QuizOption("b", "선택지 B")
			)
		);
		when(quizService.detail(1L, 50L)).thenReturn(new QuizDetailResponse(
			50L,
			100L,
			QuizType.MCQ,
			"퀴즈",
			3,
			1,
			3,
			1,
			List.of(QuizQuestionResponse.from(question)),
			false
		));
		when(quizService.list(1L, 100L)).thenReturn(new QuizListResponse(
			List.of(new QuizSummaryResponse(
				50L,
				"퀴즈",
				QuizType.MCQ,
				3,
				1,
				3,
				1,
				true,
				new BigDecimal("80.50"),
				new BigDecimal("100.00"),
				true,
				NOW
			))
		));

		mockMvc.perform(get("/api/quizzes/50")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.questions[0].questionId").value("q1"))
			.andExpect(jsonPath("$.data.submitted").value(false))
			.andExpect(content().string(not(containsString("correctOptionId"))))
			.andExpect(content().string(not(containsString("correctAnswer"))))
			.andExpect(content().string(not(containsString("referenceAnswer"))))
			.andExpect(content().string(not(containsString("modelAnswer"))))
			.andExpect(content().string(not(containsString("rubric"))));

		mockMvc.perform(get("/api/sessions/100/quizzes")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.quizzes[0].score").value(80.5))
			.andExpect(content().string(not(containsString("correctOptionId"))))
			.andExpect(content().string(not(containsString("rubric"))));
	}

	@Test
	void submitUsesDocumentedEnvelopeAndHiddenOwnershipError() throws Exception {
		GradingResult gradingResult = new GradingResult(
			"1.0",
			new BigDecimal("100.00"),
			new BigDecimal("100.00"),
			List.of(new GradingItem(
				"q1",
				new BigDecimal("100.00"),
				new BigDecimal("100.00"),
				GradingVerdict.CORRECT,
				"정확합니다."
			))
		);
		when(submissionService.submit(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(50L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(new QuizSubmitResponse(
			200L,
			50L,
			QuizType.MCQ,
			new BigDecimal("100.00"),
			new BigDecimal("100.00"),
			true,
			QuizGradingResultResponse.from(gradingResult),
			List.of(UiAction.moveNextPage())
		));

		mockMvc.perform(post("/api/quizzes/50/submit")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "requestId": "request-1",
					  "answers": [
					    {"questionId": "q1", "answer": "a"}
					  ]
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.submissionId").value(200))
			.andExpect(jsonPath("$.data.passed").value(true))
			.andExpect(jsonPath("$.data.uiActions[0].yesEvent")
				.value("MOVE_NEXT_PAGE"))
			.andExpect(jsonPath("$.data.gradingResult.schemaVersion").doesNotExist())
			.andExpect(jsonPath("$.data.gradingResult.quizId").doesNotExist())
			.andExpect(jsonPath("$.data.gradingResult.totalScore").doesNotExist())
			.andExpect(content().string(not(containsString("correctOptionId"))));

		when(quizService.detail(1L, 999L))
			.thenThrow(new BusinessException(ErrorCode.QUIZ_NOT_FOUND));
		mockMvc.perform(get("/api/quizzes/999")
				.header(HttpHeaders.AUTHORIZATION, bearer()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("QUIZ_NOT_FOUND"));
	}

	@Test
	void failedSubmitReturnsDiagnosisQuestionWithStableReferenceFields()
		throws Exception {
		GradingResult gradingResult = new GradingResult(
			"1.0",
			new BigDecimal("40.00"),
			new BigDecimal("100.00"),
			List.of()
		);
		when(submissionService.submit(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(50L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(new QuizSubmitResponse(
			200L,
			50L,
			QuizType.MCQ,
			new BigDecimal("40.00"),
			new BigDecimal("100.00"),
			false,
			QuizGradingResultResponse.from(gradingResult),
			List.of(UiAction.diagnosisQuestion(
				"왜 역수를 곱하는지가 막혔나요?",
				30L
			))
		));

		mockMvc.perform(post("/api/quizzes/50/submit")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "requestId": "request-2",
					  "answers": [
					    {"questionId": "q1", "answer": "a"}
					  ]
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.uiActions[0].type")
				.value("DIAGNOSIS_QUESTION"))
			.andExpect(jsonPath("$.data.uiActions[0].content")
				.value("왜 역수를 곱하는지가 막혔나요?"))
			.andExpect(jsonPath("$.data.uiActions[0].diagnosisId")
				.value(30))
			.andExpect(jsonPath("$.data.uiActions[0].yesEvent")
				.doesNotExist())
			.andExpect(jsonPath("$.data.uiActions[0].noEvent")
				.doesNotExist());
	}

	private String bearer() {
		return "Bearer " + accessToken;
	}
}
