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
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;

import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.CaptionsRequest;
import io.edupilot.ai.dto.CaptionsResponse;
import io.edupilot.ai.dto.CriteriaSuggestRequest;
import io.edupilot.ai.dto.DiagnosisRequest;
import io.edupilot.ai.dto.DocChatRequest;
import io.edupilot.ai.dto.ExamDraftRequest;
import io.edupilot.ai.dto.ExamDraftResponse;
import io.edupilot.exam.ExamQuestionType;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.OutlineRequest;
import io.edupilot.ai.dto.OutlineResponse;
import io.edupilot.report.ReportSourceType;
import io.edupilot.ai.dto.QuizAssessmentRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.ai.dto.ReportGenerateRequest;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
	void turnUsesPerCallReadTimeout() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnId": "turn-timeout",
			  "turnGoal": "ANSWER_USER_QUESTION",
			  "actionsExecuted": [],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": []
			}
			""").setBodyDelay(500, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.executeTurn(
				turnRequest("turn-timeout"),
				Duration.ofMillis(100)
			))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT)
			);
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void turnResponseDeserializesOptionalNoteDraft() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnId": "turn-note",
			  "turnGoal": "WRITE_NOTE",
			  "actionsExecuted": [],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": [],
			  "noteDraft": {
			    "title": "복습 노트",
			    "content": "## 핵심\\n내용"
			  }
			}
			"""));

		var response = client(Duration.ofSeconds(1))
			.executeTurn(turnRequest("turn-note"));

		assertThat(response.noteDraft().title()).isEqualTo("복습 노트");
		assertThat(response.noteDraft().content()).isEqualTo("## 핵심\n내용");
	}

	@Test
	void turnAcceptsAndLogsOptionalAdjustmentsWithoutReasonEnumValidation() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "turnId": "turn-adjusted",
			  "turnGoal": "EXPLAIN_CURRENT_PAGE",
			  "actionsExecuted": [
			    {
			      "actionId": "action-1",
			      "agent": "Orchestrator",
			      "status": "SUCCESS",
			      "adjustments": [
			        {
			          "field": "page",
			          "from": 5,
			          "to": 3,
			          "reason": "UNKNOWN_REASON_IS_PRESERVED"
			        }
			      ],
			      "artifacts": {}
			    },
			    {
			      "actionId": "action-2",
			      "agent": "ExplainerAgent",
			      "status": "SUCCESS"
			    }
			  ],
			  "messages": [],
			  "statePatch": {},
			  "uiActions": [],
			  "memoryCandidates": []
			}
			"""));
		Logger logger = (Logger) LoggerFactory.getLogger(HttpAiClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			var response = client(Duration.ofSeconds(1))
				.executeTurn(turnRequest("turn-adjusted"));

			assertThat(response.actionsExecuted().getFirst().adjustments())
				.singleElement()
				.satisfies(adjustment -> {
					assertThat(adjustment.field()).isEqualTo("page");
					assertThat(adjustment.from()).isEqualTo(5);
					assertThat(adjustment.to()).isEqualTo(3);
					assertThat(adjustment.reason())
						.isEqualTo("UNKNOWN_REASON_IS_PRESERVED");
				});
			assertThat(response.actionsExecuted().get(1).adjustments())
				.isEmpty();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"AI service call completed"
			))
			.singleElement()
			.satisfies(event -> {
				Map<String, Object> fields = event.getKeyValuePairs().stream()
					.collect(Collectors.toMap(
						pair -> pair.key,
						pair -> pair.value
					));
				assertThat(fields.get("turnId")).isEqualTo("turn-adjusted");
				assertThat(fields.get("adjustments").toString())
					.contains(
						"actionId=action-1",
						"field=page",
						"from=5",
						"to=3",
						"reason=UNKNOWN_REASON_IS_PRESERVED"
					);
			});
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
	void gradePreservesUpstreamRequestInvalidCode() {
		server.enqueue(jsonResponse(422, """
			{
			  "schemaVersion":"1.0",
			  "error":{
			    "code":"AI_REQUEST_INVALID",
			    "category":"SCHEMA",
			    "message":"invalid request",
			    "retryable":false
			  },
			  "traceId":"ai-trace"
			}
			"""));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).grade(gradeRequest()))
			.isInstanceOfSatisfying(AiClientException.class, exception -> {
				assertThat(exception.upstreamCode()).isEqualTo("AI_REQUEST_INVALID");
				assertThat(exception.retryable()).isFalse();
			});
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void docChatUsesInternalContractAndMapsSchemaEnvelope() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion":"1.0",
			  "answer":"document answer",
			  "warnings":[{"type":"CONTEXT_TRUNCATED","message":"trimmed"}]
			}
			"""));
		DocChatRequest request = new DocChatRequest(
			"1.0",
			List.of(new DocChatRequest.ContextDocument("material p.1-2", "text")),
			List.of(new DocChatRequest.HistoryMessage("USER", "previous")),
			"question"
		);

		var response = client(Duration.ofSeconds(1)).docChat(request);

		assertThat(response.answer()).isEqualTo("document answer");
		assertThat(response.warnings()).singleElement()
			.extracting(io.edupilot.ai.dto.DocChatResponse.Warning::type)
			.isEqualTo("CONTEXT_TRUNCATED");
		RecordedRequest recorded = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/internal/ai/doc-chat");
		assertThat(recorded.getHeader("X-Internal-Token"))
			.isEqualTo(INTERNAL_TOKEN);
		assertThat(recorded.getBody().readUtf8())
			.contains("\"schemaVersion\":\"1.0\"", "\"contextDocs\"", "\"history\"");

		server.enqueue(jsonResponse(422, """
			{
			  "schemaVersion":"1.0",
			  "error":{
			    "code":"AI_REQUEST_INVALID",
			    "category":"SCHEMA",
			    "message":"invalid request",
			    "retryable":false
			  },
			  "traceId":"ai-trace"
			}
			"""));
		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).docChat(request))
			.isInstanceOfSatisfying(AiClientException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
				assertThat(exception.upstreamCode())
					.isEqualTo("AI_REQUEST_INVALID");
			});
	}

	@Test
	void gradeOmitsAbsentOptionalContextFields() throws Exception {
		server.enqueue(jsonResponse(200, gradeSuccessBody()));
		GradeRequest base = gradeRequest();
		client(Duration.ofSeconds(1)).grade(new GradeRequest(
			base.schemaVersion(), base.quizId(), base.quizType(), base.items(),
			base.studentAnswers(), null, null
		));

		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getBody().readUtf8())
			.doesNotContain("pageContext")
			.doesNotContain("learnerMemoryDigest");
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
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
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
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);

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
			  ],
			  "xaiFileId": "file-123",
			  "warnings": [
			    {"type": "FUTURE_WARNING", "message": "ignored"}
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
		assertThat(response.xaiFileId()).isEqualTo("file-123");
		assertThat(response.warnings()).singleElement().satisfies(warning -> {
			assertThat(warning.type()).isEqualTo("FUTURE_WARNING");
			assertThat(warning.message()).isEqualTo("ignored");
		});
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
	void extractDefaultsMissingXaiFileMetadata() {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "pageCount": 1,
			  "pages": [{"pageNumber": 1, "text": "page"}]
			}
			"""));
		ByteArrayResource pdf = new ByteArrayResource("%PDF-test".getBytes());

		var response = client(Duration.ofSeconds(1)).extract(pdf);

		assertThat(response.xaiFileId()).isNull();
		assertThat(response.warnings()).isEmpty();
	}

	@Test
	void uploadFileSendsPdfMultipartAndReturnsValidatedFileId() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "xaiFileId": "file-backfill"
			}
			"""));
		ByteArrayResource pdf = new ByteArrayResource("%PDF-backfill".getBytes()) {
			@Override
			public String getFilename() {
				return "material.pdf";
			}
		};

		String fileId = client(Duration.ofSeconds(1)).uploadFile(pdf);

		assertThat(fileId).isEqualTo("file-backfill");
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/internal/ai/files");
		assertThat(request.getHeader("X-Internal-Token"))
			.isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
		assertThat(request.getHeader("Content-Type"))
			.startsWith("multipart/form-data");
		assertThat(request.getBody().readUtf8())
			.contains("name=\"file\"")
			.contains("filename=\"material.pdf\"")
			.contains("%PDF-backfill");
	}

	@Test
	void uploadFileRejectsBlankProviderFileId() {
		server.enqueue(jsonResponse(200, """
			{"schemaVersion":"1.0","xaiFileId":" "}
			"""));
		ByteArrayResource pdf = new ByteArrayResource("%PDF-backfill".getBytes());

		assertThatThrownBy(() -> client(Duration.ofSeconds(1)).uploadFile(pdf))
			.isInstanceOfSatisfying(AiClientException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_RESPONSE_INVALID)
			);
	}

	@Test
	void deleteFileUsesInternalTokenAndAcceptsNoContent() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(204));

		client(Duration.ofSeconds(1)).deleteFile("file-123");

		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("DELETE");
		assertThat(request.getPath()).isEqualTo("/internal/ai/files/file-123");
		assertThat(request.getHeader("X-Internal-Token"))
			.isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
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
	void outlineSendsEveryPageTextAndParsesStructuredResponse() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "materialSummary": "자료 요약입니다.",
			  "sections": [
			    {
			      "title": "핵심 단원",
			      "description": "핵심 개념과 예제를 학습합니다.",
			      "startPage": 1,
			      "endPage": 2,
			      "keywords": ["개념", "예제"]
			    }
			  ],
			  "quizCheckpoints": [
			    {
			      "triggerPage": 2,
			      "coverage": {"startPage": 1, "endPage": 2}
			    }
			  ],
			  "totalPages": 2
			}
			"""));
		OutlineRequest outlineRequest = new OutlineRequest(
			"1.0",
			"file-outline-phase-five",
			2,
			List.of(
				new OutlineRequest.Page(1, "첫 페이지 전체 텍스트"),
				new OutlineRequest.Page(2, "둘째 페이지 전체 텍스트")
			)
		);

		OutlineResponse response = client(Duration.ofSeconds(1))
			.outline(outlineRequest);

		assertThat(response.materialSummary()).isEqualTo("자료 요약입니다.");
		assertThat(response.sections()).singleElement().satisfies(section -> {
			assertThat(section.title()).isEqualTo("핵심 단원");
			assertThat(section.description())
				.isEqualTo("핵심 개념과 예제를 학습합니다.");
			assertThat(section.keywords()).containsExactly("개념", "예제");
		});
		assertThat(response.quizCheckpoints()).singleElement()
			.satisfies(checkpoint -> {
				assertThat(checkpoint.triggerPage()).isEqualTo(2);
				assertThat(checkpoint.coverage())
					.isEqualTo(new OutlineResponse.Coverage(1, 2));
			});
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getMethod()).isEqualTo("POST");
		assertThat(request.getPath()).isEqualTo("/internal/ai/outline");
		assertThat(request.getHeader("X-Internal-Token")).isEqualTo(INTERNAL_TOKEN);
		assertThat(request.getBody().readUtf8())
			.contains("\"schemaVersion\":\"1.0\"")
			.contains("\"xaiFileId\":\"file-outline-phase-five\"")
			.contains("\"totalPages\":2")
			.contains("\"pageNumber\":1")
			.contains("첫 페이지 전체 텍스트")
			.contains("둘째 페이지 전체 텍스트");
	}

	@Test
	void captionsSendsImageAndTextAndParsesNullableResults() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "captions": [
			    {"pageNumber": 1, "caption": "diagram"},
			    {"pageNumber": 2, "caption": null}
			  ],
			  "warnings": [
			    {"type": "PAGE_CAPTION_FAILED", "message": "pageNumber 2"}
			  ]
			}
			"""));
		CaptionsRequest captionsRequest = new CaptionsRequest(
			"1.0",
			List.of(
				new CaptionsRequest.Page(1, "aW1hZ2UtMQ==", "text 1"),
				new CaptionsRequest.Page(2, "aW1hZ2UtMg==", "text 2")
			)
		);

		CaptionsResponse response = client(Duration.ofSeconds(1))
			.captions(captionsRequest);

		assertThat(response.captions()).extracting(CaptionsResponse.PageCaption::caption)
			.containsExactly("diagram", null);
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request).isNotNull();
		assertThat(request.getPath()).isEqualTo("/internal/ai/captions");
		assertThat(request.getBody().readUtf8())
			.contains("\"imageBase64\":\"aW1hZ2UtMQ==\"")
			.contains("\"extractedText\":\"text 2\"");
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
		assertThat(request.getHeader("X-Trace-Id")).isEqualTo(TRACE_ID);
	}

	@Test
	void criteriaSuggestionUsesDedicatedContractAndPath() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion":"1.0",
			  "criteria":[
			    {"key":"engagement","name":"참여도","description":"설명","rubric":"루브릭","allowedSources":["SESSION"],"weight":1.0,"minimumEvidence":2},
			    {"key":"accuracy","name":"정확도","description":"설명","rubric":"루브릭","allowedSources":["QUIZ_SUBMISSION"],"weight":1.0,"minimumEvidence":2},
			    {"key":"reflection","name":"성찰","description":"설명","rubric":"루브릭","allowedSources":["MEMORY"],"weight":1.0,"minimumEvidence":2}
			  ],
			  "warnings":[]
			}
			"""));

		var response = client(Duration.ofSeconds(1)).suggestCriteria(
			new CriteriaSuggestRequest(
				"1.0",
				List.of("concept_understanding"),
				List.of(new CriteriaSuggestRequest.Material(
					"자료",
					"요약",
					List.of(new OutlineResponse.Section(
						"도입", 1, 2, List.of("개념")
					))
				))
			)
		);

		assertThat(response.criteria()).hasSize(3);
		assertThat(response.criteria().getFirst().allowedSources())
			.containsExactly(ReportSourceType.SESSION);
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getPath())
			.isEqualTo("/internal/ai/criteria/suggest");
		assertThat(request.getBody().readUtf8())
			.contains(
				"\"existingCriterionKeys\":[\"concept_understanding\"]",
				"\"materialSummary\":\"요약\""
			);
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

	@Test
	void aiFailureLogContainsOnlyStructuredSafeMetadata() {
		server.enqueue(jsonResponse(401, errorBody("AUTH", false)));
		Logger logger = (Logger) LoggerFactory.getLogger(HttpAiClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertThatThrownBy(() ->
				client(Duration.ofSeconds(1))
					.executeTurn(turnRequest("turn-structured-log")))
				.isInstanceOf(AiClientException.class);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event ->
				event.getFormattedMessage().equals("AI service call failed")
			)
			.singleElement()
			.satisfies(event -> {
				Map<String, String> fields = event.getKeyValuePairs().stream()
					.collect(Collectors.toMap(
						pair -> pair.key,
						pair -> String.valueOf(pair.value)
					));
				assertThat(fields)
					.containsEntry("endpoint", "/internal/ai/turn")
					.containsEntry("status", "FAILED")
					.containsEntry("category", "AUTH")
					.containsEntry("errorCode", "INTERNAL_SERVER_ERROR")
					.containsEntry("attempt", "1")
					.containsEntry("retried", "false")
					.containsKey("durationMs")
					.containsEntry("turnId", "turn-structured-log")
					.containsEntry("sessionId", "100");
				assertThat(eventText(event)).doesNotContain(
					INTERNAL_TOKEN,
					"token mismatch",
					"secret-value"
				);
			});
	}

	@Test
	void retryLogsEachAttemptAndMarksTheRetriedCall() {
		server.enqueue(jsonResponse(503, errorBody("INTERNAL", true)));
		server.enqueue(jsonResponse(200, gradeSuccessBody()));
		Logger logger = (Logger) LoggerFactory.getLogger(HttpAiClient.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			client(Duration.ofSeconds(1)).grade(gradeRequest());
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		List<Map<String, String>> calls = appender.list.stream()
			.filter(event -> event.getFormattedMessage().startsWith(
				"AI service call"
			))
			.map(event -> event.getKeyValuePairs().stream()
				.collect(Collectors.toMap(
					pair -> pair.key,
					pair -> String.valueOf(pair.value)
				)))
			.toList();
		assertThat(calls).hasSize(2);
		assertThat(calls.get(0))
			.containsEntry("status", "FAILED")
			.containsEntry("attempt", "1")
			.containsEntry("retried", "false");
		assertThat(calls.get(1))
			.containsEntry("status", "SUCCESS")
			.containsEntry("attempt", "2")
			.containsEntry("retried", "true");
	}

	@Test
	void reportRejectsUnknownResponseField() {
		server.enqueue(jsonResponse(200, reportSuccessBody().replace(
			"\"usage\":",
			"\"unexpected\": true, \"usage\":"
		)));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.generateReport(reportRequest()))
			.isInstanceOf(AiClientException.class)
			.satisfies(exception -> assertThat(
				((AiClientException)exception).errorCode()
			).isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void reportRejectsInvalidJson() {
		server.enqueue(jsonResponse(200, "{invalid-json"));

		assertThatThrownBy(() -> client(Duration.ofSeconds(1))
			.generateReport(reportRequest()))
			.isInstanceOf(AiClientException.class)
			.satisfies(exception -> assertThat(
				((AiClientException)exception).errorCode()
			).isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
	}

	@Test
	void reportUsesDedicatedReadTimeout() {
		server.enqueue(jsonResponse(200, reportSuccessBody())
			.setBodyDelay(500, TimeUnit.MILLISECONDS));

		assertThatThrownBy(() -> client(Duration.ofMillis(100))
			.generateReport(reportRequest()))
			.isInstanceOf(AiClientException.class)
			.satisfies(exception -> assertThat(
				((AiClientException)exception).errorCode()
			).isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT));
	}

	@Test
	void examDraftUsesDedicatedEndpointAndQuestionTypeDiscriminator() throws Exception {
		server.enqueue(jsonResponse(200, """
			{
			  "schemaVersion": "1.0",
			  "examId": 12,
			  "questions": [
			    {
			      "questionType": "MCQ",
			      "sourcePageNumber": 1,
			      "questionId": "mcq-1",
			      "questionText": "Question",
			      "points": 5,
			      "choices": [
			        {"choiceId": "a", "text": "A"},
			        {"choiceId": "b", "text": "B"}
			      ],
			      "answerChoiceId": "a",
			      "explanation": "Because A"
			    },
			    {
			      "questionType": "SHORT",
			      "sourcePageNumber": null,
			      "questionId": "short-1",
			      "questionText": "Explain",
			      "points": 5,
			      "referenceAnswer": "Answer",
			      "gradingCriteria": ["Accuracy"]
			    }
			  ],
			  "usage": {
			    "model": "grok-test",
			    "inputTokens": 10,
			    "outputTokens": 20,
			    "reasoningTokens": null
			  }
			}
			"""));

		ExamDraftResponse response = client(Duration.ofSeconds(1))
			.generateExamDraft(new ExamDraftRequest(
				"1.0",
				12L,
				List.of(new ExamDraftRequest.PageContext(1, "Page text")),
				List.of(
					new ExamDraftRequest.QuestionPlanItem(ExamQuestionType.MCQ, 1),
					new ExamDraftRequest.QuestionPlanItem(ExamQuestionType.SHORT, 1)
				)
			));

		assertThat(response.questions().get(0))
			.isInstanceOf(ExamDraftResponse.McqQuestion.class);
		assertThat(response.questions().get(1))
			.isInstanceOf(ExamDraftResponse.ShortQuestion.class);
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertThat(request.getPath()).isEqualTo("/internal/ai/exams/draft");
		assertThat(request.getBody().readUtf8())
			.contains("\"examId\":12", "\"pageContexts\"");
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
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
			readTimeout,
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

	private ReportGenerateRequest reportRequest() {
		return new ReportGenerateRequest(
			"1.0",
			"report-1",
			"generation-1",
			new ReportGenerateRequest.Scope("전체 기간", null, null),
			List.of(),
			new ReportGenerateRequest.DataQuality(
				"1.0",
				List.of(ReportGenerateRequest.EvidenceSourceType.QA),
				List.of(ReportGenerateRequest.EvidenceSourceType.EXAM),
				List.of(new ReportGenerateRequest.CriterionEligibility(
					"question_specificity", true, null
				))
			),
			List.of(new ReportGenerateRequest.Criterion(
				"question_specificity",
				"질문 구체성",
				"질문의 구체성을 평가",
				"질문의 구체성을 평가",
				List.of(ReportGenerateRequest.EvidenceSourceType.QA),
				1,
				1
			)),
			List.of(new ReportGenerateRequest.Evidence(
				"evidence-1",
				ReportGenerateRequest.EvidenceSourceType.QA,
				"2026-08-03T00:00:00Z",
				"구체적인 질문",
				"{\"characterCount\":20}"
			)),
			null
		);
	}

	private String reportSuccessBody() {
		return """
			{
			  "schemaVersion": "1.0",
			  "reportId": "report-1",
			  "criterionResults": [{
			    "criterionKey": "question_specificity",
			    "status": "ASSESSED",
			    "score": 80,
			    "narrative": "평가 서술",
			    "evidenceIds": ["evidence-1"]
			  }],
			  "summary": {
			    "overview": "요약",
			    "strengths": [],
			    "improvements": [],
			    "misconceptionCandidates": [],
			    "recommendedActions": []
			  },
			  "warnings": [],
			  "usage": {
			    "model": "test-model",
			    "inputTokens": 10,
			    "outputTokens": 20,
			    "reasoningTokens": null
			  }
			}
			""";
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

	@Test
	void turnExposesOnlyRemoteRetryableTimeoutToCaller() {
		server.enqueue(jsonResponse(
			504,
			errorBody("TIMEOUT", true)
		));

		assertThatThrownBy(() ->
			client(Duration.ofSeconds(1))
				.executeTurn(turnRequest("turn-123")))
			.isInstanceOfSatisfying(AiClientException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_SERVICE_TIMEOUT);
				assertThat(exception.retryable()).isTrue();
			});
		assertThat(server.getRequestCount()).isEqualTo(1);
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

	private String eventText(ILoggingEvent event) {
		return event.getFormattedMessage()
			+ event.getKeyValuePairs()
			+ event.getMDCPropertyMap();
	}
}
