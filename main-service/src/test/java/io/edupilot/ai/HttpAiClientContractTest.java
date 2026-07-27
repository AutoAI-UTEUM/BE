package io.edupilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.io.ByteArrayResource;

import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.DiagnosisRequest;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.QuizAssessmentRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class HttpAiClientContractTest {

	private static final String INTERNAL_TOKEN = "contract-test-token";
	private static final String TRACE_ID = "contract-test-trace";

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, TRACE_ID);
	}

	@AfterEach
	void tearDown() throws IOException {
		MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
		server.shutdown();
	}

	@Test
	void turnResponseUsesMinimumContractAndPropagatesInternalHeaders() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnId": "turn-123",
			  "turnGoal": "ANSWER_USER_QUESTION",
			  "actionsExecuted": [],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": []
			}
			"""));

		var response = client(Duration.ofSeconds(1))
			.executeTurn(turnRequest("turn-123"));

		assertThat(response.turnId()).isEqualTo("turn-123");
		assertThat(response.turnGoal()).isEqualTo("ANSWER_USER_QUESTION");

		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/internal/ai/turn");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void gradeUsesDedicatedContractAndPropagatesInternalHeaders() throws Exception {
		server.enqueue(jsonResponse(200, gradeSuccessBody()));

		var response = client(Duration.ofSeconds(1)).grade(gradeRequest());

		assertThat(response.quizId()).isEqualTo(50L);
		assertThat(response.score()).isEqualByComparingTo("10.00");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo("/internal/ai/grade");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
		assertThat(request.getBody().readUtf8())
			.contains("\"schemaVersion\":\"1.0\"")
			.contains("\"quizId\":50")
			.contains("\"studentAnswers\"");
	}

	@Test
	void gradeRetriesOnlyRetryableInternalErrorOnce() {
		server.enqueue(jsonResponse(503, errorBody("INTERNAL", true)));
		server.enqueue(jsonResponse(200, gradeSuccessBody()));

		var response = client(Duration.ofSeconds(1)).grade(gradeRequest());

		assertThat(response.score()).isEqualByComparingTo("10.00");
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void gradeRetriesRetryableTimeoutOnlyOnce() {
		server.enqueue(jsonResponse(504, errorBody("TIMEOUT", true)));
		server.enqueue(jsonResponse(200, gradeSuccessBody()));

		var response = client(Duration.ofSeconds(1)).grade(gradeRequest());

		assertThat(response.score()).isEqualByComparingTo("10.00");
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void gradeDoesNotRetryWhenRemoteMarksErrorNonRetryable() {
		server.enqueue(jsonResponse(504, errorBody("TIMEOUT", false)));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).grade(gradeRequest()))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void gradeNeverRetriesSchemaErrorEvenWhenRemoteFlagIsTrue() {
		server.enqueue(jsonResponse(422, errorBody("SCHEMA", true)));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).grade(gradeRequest()))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void gradeTransportTimeoutWithoutRemoteFlagIsNotRetried() {
		server.enqueue(jsonResponse(200, gradeSuccessBody())
			.setBodyDelay(500, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() ->
			client(Duration.ofMillis(100)).grade(gradeRequest()))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void assessmentUsesDedicatedContractAndValidatesNumericConfidence()
		throws Exception {
		server.enqueue(jsonResponse(200, assessmentSuccessBody()));

		var response = client(Duration.ofSeconds(1))
			.quizAssessment(assessmentRequest());

		assertThat(response.memoryCandidates())
			.singleElement()
			.extracting(QuizAssessmentResponse.MemoryCandidate::confidence)
			.isEqualTo(new BigDecimal("0.80"));
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo(
			"/internal/ai/quiz-assessment"
		);
		assertThat(request.getBody().readUtf8())
			.contains("\"schemaVersion\":\"1.0\"")
			.contains("\"quizResult\"")
			.contains("\"studentAnswers\"");
	}

	@Test
	void assessmentRetriesRetryableInternalErrorOnce() {
		server.enqueue(jsonResponse(503, errorBody("INTERNAL", true)));
		server.enqueue(jsonResponse(200, assessmentSuccessBody()));

		client(Duration.ofSeconds(1)).quizAssessment(assessmentRequest());

		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void assessmentRejectsConfidenceOutsideZeroToOne() {
		server.enqueue(jsonResponse(200, assessmentSuccessBody()
			.replace("\"confidence\": 0.80", "\"confidence\": 1.10")));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.quizAssessment(assessmentRequest()))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void diagnosisUsesDedicatedContractAndRejectsBlankPrompt()
		throws Exception {
		server.enqueue(jsonResponse(200, diagnosisSuccessBody()));

		var response = client(Duration.ofSeconds(1))
			.diagnosis(diagnosisRequest());

		assertThat(response.diagnosticPrompt()).contains("막혔나요");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo("/internal/ai/diagnosis");

		server.enqueue(jsonResponse(200, diagnosisSuccessBody()
			.replace(
				"\"왜 역수를 곱하는지가 막혔나요?\"",
				"\" \""
			)));
		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.diagnosis(diagnosisRequest()))
			.isInstanceOf(AiClientException.class);
	}

	@Test
	void pipelineTransportTimeoutIsNotRetried() {
		server.enqueue(jsonResponse(200, assessmentSuccessBody())
			.setBodyDelay(500, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofMillis(100))
			.quizAssessment(assessmentRequest()))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void extractSendsPdfMultipartAndValidatesResponse() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 2,
			  "pages": [
			    {"pageNumber": 1, "text": "first"},
			    {"pageNumber": 2, "text": "second"}
			  ]
			}
			"""));

		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};
		var response = client(Duration.ofSeconds(1)).extract(pdf);

		assertThat(response.pageCount()).isEqualTo(2);
		assertThat(response.pages()).extracting("pageNumber").containsExactly(1, 2);
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo("/internal/ai/extract");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
		assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
		assertThat(request.getBody().readUtf8())
			.contains("name=\"file\"")
			.contains("filename=\"material.pdf\"")
			.contains("%PDF-test");
	}

	@Test
	void extractRejectsNonContiguousPageNumbers() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 1,
			  "pages": [{"pageNumber": 2, "text": "wrong"}]
			}
			"""));

		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).extract(pdf))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
	}

	@Test
	void delayedExtractResponseUsesExtractTimeoutWithoutRetry() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 1,
			  "pages": [{"pageNumber": 1, "text": "page"}]
			}
			""").setBodyDelay(500, TimeUnit.MILLISECONDS));
		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};

		assertThatThrownBy(() -> client(Duration.ofMillis(100)).extract(pdf))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void healthResponseIsParsedFromConfiguredPath() throws Exception {
		server.enqueue(jsonResponse(200, """
			{"status": "UP"}
			"""));

		var response = client(Duration.ofSeconds(1)).health();

		assertThat(response.status()).isEqualTo("UP");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("GET");
		assertThat(request.getPath()).isEqualTo("/health");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
	}

	@Test
	void unavailableServerMapsToServiceUnavailableWithoutRetry() throws IOException {
		int unusedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		}
		HttpAiClient client = new HttpAiClient(properties(
			URI.create("http://127.0.0.1:" + unusedPort),
			Duration.ofMillis(200)
		));

		assertThatThrownBy(client::health)
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_UNAVAILABLE));
	}

	@Test
	void delayedResponseMapsToServiceTimeoutWithoutRetry() {
		server.enqueue(jsonResponse(200, """
			{"status": "UP"}
			""").setBodyDelay(500, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofMillis(100)).health())
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void malformedSuccessBodyMapsToInvalidResponse() {
		server.enqueue(new MockResponse()
			.setResponseCode(200)
			.setHeader("Content-Type", "text/html")
			.setBody("<html>not-json</html>"));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).health())
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void missingRequiredTurnFieldMapsToInvalidResponse() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnGoal": "ANSWER_USER_QUESTION",
			  "actionsExecuted": [],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": []
			}
			"""));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void schemaErrorCategoryMapsToInvalidResponse() {
		server.enqueue(jsonResponse(422, errorBody("SCHEMA", false)));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void authenticationErrorMapsToInternalErrorWithoutLeakingRemoteMessage() {
		server.enqueue(jsonResponse(401, errorBody("AUTH", false)));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1)).executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR);
				assertThat(exception.clientMessage())
					.isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.message())
					.doesNotContain("token mismatch");
			});
	}

	private HttpAiClient client(Duration readTimeout) {
		return new HttpAiClient(properties(server.url("/").uri(), readTimeout));
	}

	private AiClientProperties properties(URI baseUrl, Duration readTimeout) {
		return new AiClientProperties(
			baseUrl,
			INTERNAL_TOKEN,
			Duration.ofMillis(300),
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
			"/health"
		);
	}

	private TurnRequest turnRequest(String turnId) {
		return new TurnRequest(
			"1.0",
			turnId,
			Map.of("sessionId", 100),
			Map.of("eventType", "USER_QUESTION", "payload", Map.of()),
			Map.of()
		);
	}

	private GradeRequest gradeRequest() {
		return new GradeRequest(
			"1.0",
			50L,
			"SHORT",
			List.of(new GradeRequest.Item(
				"q1",
				"문항",
				"기준 답안",
				List.of(new GradeRequest.Rubric(
					"정확성",
					BigDecimal.ONE
				)),
				new BigDecimal("10.00")
			)),
			List.of(new GradeRequest.StudentAnswer("q1", "학생 답안")),
			new GradeRequest.PageContext(1, 1, "페이지 문맥"),
			null
		);
	}

	private QuizAssessmentRequest assessmentRequest() {
		return new QuizAssessmentRequest(
			"1.0",
			new QuizAssessmentRequest.QuizResult(
				50L,
				"SHORT",
				new BigDecimal("4.00"),
				new BigDecimal("10.00"),
				false,
				List.of(new QuizAssessmentRequest.ResultItem(
					"q1",
					new BigDecimal("4.00"),
					new BigDecimal("10.00"),
					"WRONG",
					"개념을 다시 확인해 보세요."
				))
			),
			List.of(new QuizAssessmentRequest.QuizItem(
				"q1",
				"문항",
				"기준 답안",
				new BigDecimal("10.00")
			)),
			List.of(new QuizAssessmentRequest.StudentAnswer(
				"q1",
				"학생 답안"
			)),
			new QuizAssessmentRequest.PageContext(1, 1, "페이지 문맥"),
			null
		);
	}

	private DiagnosisRequest diagnosisRequest() {
		QuizAssessmentResponse assessment = new QuizAssessmentResponse(
			"1.0",
			"분수 나눗셈 이해가 부족합니다.",
			List.of(),
			List.of("역수 개념"),
			List.of("절차만 암기함"),
			"REVIEW",
			List.of(),
			List.of("q1 오답"),
			null
		);
		return new DiagnosisRequest(
			"1.0",
			assessment,
			assessmentRequest().quizResult(),
			List.of(new DiagnosisRequest.WrongItem(
				"q1",
				"문항",
				"학생 답안",
				"기준 답안",
				"개념을 다시 확인해 보세요."
			)),
			assessmentRequest().pageContext(),
			null
		);
	}

	private String gradeSuccessBody() {
		return """
			{
			  "schemaVersion": "1.0",
			  "quizId": 50,
			  "quizType": "SHORT",
			  "score": 10.00,
			  "maxScore": 10.00,
			  "items": [
			    {
			      "questionId": "q1",
			      "score": 10.00,
			      "maxScore": 10.00,
			      "verdict": "CORRECT",
			      "feedback": "정확합니다."
			    }
			  ],
			  "usage": null
			}
			""";
	}

	private String assessmentSuccessBody() {
		return """
			{
			  "schemaVersion": "1.0",
			  "understandingSummary": "분수 나눗셈 이해가 부족합니다.",
			  "strengths": [],
			  "weaknesses": ["역수 개념"],
			  "suspectedMisconceptions": ["절차만 암기함"],
			  "recommendedNextDirection": "REVIEW",
			  "memoryCandidates": [
			    {
			      "type": "WEAKNESS",
			      "content": "역수의 의미를 어려워함",
			      "confidence": 0.80
			    }
			  ],
			  "evidence": ["q1 오답"],
			  "usage": null
			}
			""";
	}

	private String diagnosisSuccessBody() {
		return """
			{
			  "schemaVersion": "1.0",
			  "focusConcepts": ["역수"],
			  "suspectedMisconceptions": ["절차만 암기함"],
			  "diagnosticPrompt": "왜 역수를 곱하는지가 막혔나요?",
			  "evidence": ["q1 오답"],
			  "repairHint": "나눗셈 상황과 연결",
			  "usage": null
			}
			""";
	}

	private MockResponse jsonResponse(int status, String body) {
		return new MockResponse()
			.setResponseCode(status)
			.setHeader("Content-Type", "application/json")
			.setBody(body);
	}

	private String errorBody(String category, boolean retryable) {
		return """
			{
			  "schemaVersion": "1.0",
			  "error": {
			    "code": "REMOTE_ERROR",
			    "category": "%s",
			    "message": "token mismatch: secret-value",
			    "retryable": %s
			  },
			  "traceId": "ai-trace"
			}
			""".formatted(category, retryable);
	}
}
