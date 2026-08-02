package io.edupilot.ai;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.edupilot.ai.dto.AiErrorResponse;
import io.edupilot.ai.dto.AiHealthResponse;
import io.edupilot.ai.dto.ActionExecuted;
import io.edupilot.ai.dto.Adjustment;
import io.edupilot.ai.dto.DiagnosisRequest;
import io.edupilot.ai.dto.DiagnosisResponse;
import io.edupilot.ai.dto.ExtractResponse;
import io.edupilot.ai.dto.ExtractedPage;
import io.edupilot.ai.dto.GradeRequest;
import io.edupilot.ai.dto.GradeResponse;
import io.edupilot.ai.dto.QuizAssessmentRequest;
import io.edupilot.ai.dto.QuizAssessmentResponse;
import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.ai.dto.TurnResponse;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpAiClient implements AiClient {

	private static final Logger log = LoggerFactory.getLogger(HttpAiClient.class);
	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
	private static final String TURN_PATH = "/internal/ai/turn";
	private static final String EXTRACT_PATH = "/internal/ai/extract";
	private static final String GRADE_PATH = "/internal/ai/grade";
	private static final String QUIZ_ASSESSMENT_PATH =
		"/internal/ai/quiz-assessment";
	private static final String DIAGNOSIS_PATH = "/internal/ai/diagnosis";
	private static final String SCHEMA_VERSION = "1.0";
	private static final MediaType NDJSON =
		MediaType.parseMediaType("application/x-ndjson");
	private static final Set<String> STREAM_STAGES = Set.of(
		"PLANNING",
		"EXPLAINING",
		"ANSWERING",
		"FINALIZING"
	);

	private final RestClient restClient;
	private final RestClient streamRestClient;
	private final RestClient healthRestClient;
	private final RestClient extractRestClient;
	private final RestClient gradeRestClient;
	private final RestClient pipelineRestClient;
	private final String healthPath;
	private final Duration streamIdleTimeout;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public HttpAiClient(AiClientProperties properties) {
		// TODO ai-integration-contract v0.3에서 예산 확정 전까지 비멱등 turn 호출은 재시도하지 않는다.
		this.restClient = buildRestClient(
			properties,
			properties.turnReadTimeout()
		);
		this.streamRestClient = buildRestClient(
			properties,
			properties.streamIdleTimeout()
		);
		this.healthRestClient = buildRestClient(
			properties,
			properties.healthTimeout(),
			properties.healthTimeout()
		);
		this.extractRestClient = buildRestClient(
			properties,
			properties.extractReadTimeout()
		);
		this.gradeRestClient = buildRestClient(
			properties,
			properties.gradeReadTimeout()
		);
		this.pipelineRestClient = buildRestClient(
			properties,
			properties.pipelineReadTimeout()
		);
		this.healthPath = properties.healthPath();
		this.streamIdleTimeout = properties.streamIdleTimeout();
	}

	private RestClient buildRestClient(
		AiClientProperties properties,
		java.time.Duration readTimeout
	) {
		return buildRestClient(
			properties,
			properties.connectTimeout(),
			readTimeout
		);
	}

	private RestClient buildRestClient(
		AiClientProperties properties,
		java.time.Duration connectTimeout,
		java.time.Duration readTimeout
	) {
		SimpleClientHttpRequestFactory requestFactory =
			new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeout);
		requestFactory.setReadTimeout(readTimeout);

		return RestClient.builder()
			.baseUrl(properties.baseUrl().toString())
			.requestFactory(requestFactory)
			.requestInterceptor((request, body, execution) -> {
				request.getHeaders().set(INTERNAL_TOKEN_HEADER, properties.internalToken());
				String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
				if (StringUtils.hasText(traceId)) {
					request.getHeaders().set(
						TraceIdFilter.TRACE_ID_HEADER,
						traceId
					);
				}
				return execution.execute(request, body);
			})
			.build();
	}

	@Override
	public AiHealthResponse health() {
		return executeAttempt(
			new AiCallContext(healthPath, 1, false, null, null, null),
			() -> {
			AiHealthResponse response = healthRestClient.get()
				.uri(healthPath)
				.retrieve()
				.body(AiHealthResponse.class);
			if (response == null || !"UP".equals(response.status())) {
				throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
			}
			return response;
			}
		);
	}

	@Override
	public TurnResponse executeTurn(TurnRequest request) {
		return executeAttempt(
			new AiCallContext(
				TURN_PATH,
				1,
				false,
				request.turnId(),
				sessionId(request),
				null
			),
			() -> {
			TurnResponse response = restClient.post()
				.uri(TURN_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(TurnResponse.class);
			validateTurnResponse(response, request);
			return response;
			}
		);
	}

	@Override
	public TurnResponse executeTurnStream(
		TurnRequest request,
		Consumer<TurnStreamEvent> listener,
		AiStreamCancellation cancellation,
		Duration totalTimeout
	) {
		if (totalTimeout == null
			|| totalTimeout.isZero()
			|| totalTimeout.isNegative()) {
			throw streamTimeout(null);
		}
		long streamStartedNanos = System.nanoTime();
		return executeAttempt(
			new AiCallContext(
				TURN_PATH,
				1,
				false,
				request.turnId(),
				sessionId(request),
				null
			),
			() -> streamRestClient.post()
				.uri(TURN_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(NDJSON)
				.body(request)
				.exchange((httpRequest, response) ->
					readTurnStream(
						response.getStatusCode(),
						response.getHeaders().getContentType(),
						response.getBody(),
						request,
						listener,
						cancellation,
						remainingTimeout(
							totalTimeout,
							streamStartedNanos
						)
					)
				)
		);
	}

	private Duration remainingTimeout(
		Duration totalTimeout,
		long streamStartedNanos
	) {
		long remainingNanos = totalTimeout.toNanos()
			- (System.nanoTime() - streamStartedNanos);
		if (remainingNanos <= 0) {
			throw streamTimeout(null);
		}
		return Duration.ofNanos(remainingNanos);
	}

	private TurnResponse readTurnStream(
		HttpStatusCode status,
		MediaType contentType,
		InputStream body,
		TurnRequest request,
		Consumer<TurnStreamEvent> listener,
		AiStreamCancellation cancellation,
		Duration totalTimeout
	) throws IOException {
		if (!status.is2xxSuccessful()) {
			throw mapStreamErrorResponse(status, body);
		}
		if (contentType == null || !NDJSON.isCompatibleWith(contentType)) {
			throw invalidStream(null);
		}

		AtomicReference<StreamTimeout> timeout = new AtomicReference<>();
		AtomicReference<ScheduledFuture<?>> idleTask = new AtomicReference<>();
		ScheduledExecutorService scheduler =
			Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform()
					.daemon()
					.name("ai-stream-timeout")
					.factory()
			);
		cancellation.bind(body);
		ScheduledFuture<?> totalTask = scheduleTimeout(
			scheduler,
			totalTimeout,
			StreamTimeout.TOTAL,
			timeout,
			body
		);
		resetIdleTimeout(scheduler, idleTask, timeout, body);
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(body, StandardCharsets.UTF_8)
		)) {
			TurnResponse completed = null;
			AiClientException terminalError = null;
			StringBuilder deltas = new StringBuilder();
			boolean terminalSeen = false;
			String line;
			while ((line = reader.readLine()) != null) {
				if (timeout.get() != null) {
					throw streamTimeout(null);
				}
				if (cancellation.isCancelled()) {
					throw streamInterrupted(null);
				}
				if (line.isBlank() || terminalSeen) {
					throw invalidStream(null);
				}

				JsonNode event = parseStreamLine(line);
				String type = requiredText(event, "type");
				resetIdleTimeout(scheduler, idleTask, timeout, body);
				switch (type) {
					case "status" -> {
						requireFields(event, Set.of("type", "stage"));
						String stage = requiredText(event, "stage");
						if (!STREAM_STAGES.contains(stage)) {
							throw invalidStream(null);
						}
						listener.accept(TurnStreamEvent.status(stage));
					}
					case "thought_summary" -> {
						requireFields(event, Set.of("type", "text"));
						listener.accept(TurnStreamEvent.thoughtSummary(
							requiredText(event, "text")
						));
					}
					case "content_delta" -> {
						requireFields(event, Set.of("type", "text"));
						String text = textual(event, "text");
						deltas.append(text);
						listener.accept(TurnStreamEvent.contentDelta(text));
					}
					case "heartbeat" -> {
						requireFields(event, Set.of("type"));
						listener.accept(TurnStreamEvent.heartbeat());
					}
					case "completed" -> {
						requireFields(event, Set.of("type", "result"));
						completed = parseCompleted(event.get("result"));
						terminalSeen = true;
					}
					case "error" -> {
						requireFields(event, Set.of(
							"type",
							"code",
							"category",
							"message",
							"retryable"
						));
						terminalError = parseStreamError(event);
						terminalSeen = true;
					}
					default -> throw invalidStream(null);
				}
			}
			if (timeout.get() != null) {
				throw streamTimeout(null);
			}
			if (cancellation.isCancelled()) {
				throw streamInterrupted(null);
			}
			if (terminalError != null) {
				throw terminalError;
			}
			if (completed == null) {
				throw streamInterrupted(null);
			}
			validateTurnResponse(completed, request);
			String completedContent = completedContent(completed);
			if (!deltas.toString().equals(completedContent)) {
				throw invalidStream(null);
			}
			return completed;
		} catch (IOException exception) {
			if (timeout.get() != null) {
				throw streamTimeout(exception);
			}
			if (cancellation.isCancelled()) {
				throw streamInterrupted(exception);
			}
			throw streamInterrupted(exception);
		} finally {
			ScheduledFuture<?> currentIdle = idleTask.getAndSet(null);
			if (currentIdle != null) {
				currentIdle.cancel(false);
			}
			totalTask.cancel(false);
			scheduler.shutdownNow();
			cancellation.unbind(body);
		}
	}

	private String completedContent(TurnResponse response) {
		StringBuilder value = new StringBuilder();
		for (Map<String, Object> message : response.messages()) {
			if (message == null
				|| !(message.get("content") instanceof String content)) {
				throw invalidStream(null);
			}
			value.append(content);
		}
		return value.toString();
	}

	private ScheduledFuture<?> scheduleTimeout(
		ScheduledExecutorService scheduler,
		Duration delay,
		StreamTimeout kind,
		AtomicReference<StreamTimeout> timeout,
		Closeable body
	) {
		return scheduler.schedule(() -> {
			if (timeout.compareAndSet(null, kind)) {
				closeQuietly(body);
			}
		}, delay.toNanos(), TimeUnit.NANOSECONDS);
	}

	private void resetIdleTimeout(
		ScheduledExecutorService scheduler,
		AtomicReference<ScheduledFuture<?>> idleTask,
		AtomicReference<StreamTimeout> timeout,
		Closeable body
	) {
		ScheduledFuture<?> next = scheduleTimeout(
			scheduler,
			streamIdleTimeout,
			StreamTimeout.IDLE,
			timeout,
			body
		);
		ScheduledFuture<?> previous = idleTask.getAndSet(next);
		if (previous != null) {
			previous.cancel(false);
		}
	}

	private JsonNode parseStreamLine(String line) {
		try {
			JsonNode event = objectMapper.readTree(line);
			if (event == null || !event.isObject()) {
				throw invalidStream(null);
			}
			return event;
		} catch (AiClientException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw invalidStream(exception);
		}
	}

	private TurnResponse parseCompleted(JsonNode result) {
		if (result == null || !result.isObject()) {
			throw invalidStream(null);
		}
		try {
			return objectMapper.treeToValue(result, TurnResponse.class);
		} catch (RuntimeException exception) {
			throw invalidStream(exception);
		}
	}

	private AiClientException parseStreamError(JsonNode event) {
		requiredText(event, "code");
		requiredText(event, "message");
		AiFailureCategory category;
		try {
			category = AiFailureCategory.valueOf(
				requiredText(event, "category")
			);
		} catch (IllegalArgumentException exception) {
			throw invalidStream(exception);
		}
		JsonNode retryableNode = event.get("retryable");
		if (retryableNode == null || !retryableNode.isBoolean()) {
			throw invalidStream(null);
		}
		boolean retryable = retryableNode.booleanValue()
			&& (category == AiFailureCategory.TIMEOUT
				|| category == AiFailureCategory.INTERNAL);
		return new AiClientException(
			errorCode(category),
			category,
			retryable,
			null
		);
	}

	private AiClientException mapStreamErrorResponse(
		HttpStatusCode status,
		InputStream body
	) {
		try {
			AiErrorResponse response = objectMapper.readValue(
				body,
				AiErrorResponse.class
			);
			if (response == null
				|| response.error() == null
				|| response.error().category() == null) {
				return streamStatusFallback(status, null);
			}
			AiFailureCategory category = AiFailureCategory.valueOf(
				response.error().category().name()
			);
			boolean retryable = response.error().retryable()
				&& (category == AiFailureCategory.TIMEOUT
					|| category == AiFailureCategory.INTERNAL);
			return new AiClientException(
				errorCode(category),
				category,
				retryable,
				null
			);
		} catch (RuntimeException exception) {
			return streamStatusFallback(status, exception);
		}
	}

	private AiClientException streamStatusFallback(
		HttpStatusCode status,
		Throwable cause
	) {
		if (status.value() == 401 || status.value() == 403) {
			return new AiClientException(
				ErrorCode.INTERNAL_SERVER_ERROR,
				AiFailureCategory.AUTH,
				false,
				cause
			);
		}
		return new AiClientException(
			ErrorCode.AI_SERVICE_UNAVAILABLE,
			AiFailureCategory.INTERNAL,
			status.is5xxServerError(),
			cause
		);
	}

	private ErrorCode errorCode(AiFailureCategory category) {
		return switch (category) {
			case TIMEOUT -> ErrorCode.AI_SERVICE_TIMEOUT;
			case SCHEMA -> ErrorCode.AI_RESPONSE_INVALID;
			case POLICY -> ErrorCode.AI_POLICY_REJECTED;
			case INTERNAL -> ErrorCode.AI_SERVICE_UNAVAILABLE;
			case AUTH -> ErrorCode.INTERNAL_SERVER_ERROR;
		};
	}

	private void requireFields(JsonNode event, Set<String> expected) {
		@SuppressWarnings("unchecked")
		Map<String, Object> values = objectMapper.convertValue(
			event,
			Map.class
		);
		if (!values.keySet().equals(expected)) {
			throw invalidStream(null);
		}
	}

	private String requiredText(JsonNode event, String field) {
		String value = textual(event, field);
		if (value.isBlank()) {
			throw invalidStream(null);
		}
		return value;
	}

	private String textual(JsonNode event, String field) {
		JsonNode value = event.get(field);
		if (value == null || !value.isTextual()) {
			throw invalidStream(null);
		}
		return value.textValue();
	}

	private AiClientException invalidStream(Throwable cause) {
		return new AiClientException(
			ErrorCode.AI_RESPONSE_INVALID,
			AiFailureCategory.SCHEMA,
			false,
			cause
		);
	}

	private AiClientException streamInterrupted(Throwable cause) {
		return new AiClientException(
			ErrorCode.AI_STREAM_INTERRUPTED,
			AiFailureCategory.INTERNAL,
			true,
			cause
		);
	}

	private AiClientException streamTimeout(Throwable cause) {
		return new AiClientException(
			ErrorCode.AI_SERVICE_TIMEOUT,
			AiFailureCategory.TIMEOUT,
			true,
			cause
		);
	}

	private void closeQuietly(Closeable body) {
		try {
			body.close();
		} catch (IOException ignored) {
			// Timeout cancellation only needs to unblock the current read.
		}
	}

	private enum StreamTimeout {
		IDLE,
		TOTAL
	}

	@Override
	public ExtractResponse extract(Resource pdfResource) {
		return executeAttempt(
			new AiCallContext(EXTRACT_PATH, 1, false, null, null, null),
			() -> {
			HttpHeaders partHeaders = new HttpHeaders();
			partHeaders.setContentType(MediaType.APPLICATION_PDF);
			partHeaders.setContentDispositionFormData(
				"file",
				StringUtils.hasText(pdfResource.getFilename())
					? pdfResource.getFilename()
					: "material.pdf"
			);
			MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
			body.add("file", new HttpEntity<>(pdfResource, partHeaders));

			ExtractResponse response = extractRestClient.post()
				.uri(EXTRACT_PATH)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(body)
				.retrieve()
				.body(ExtractResponse.class);
			validateExtractResponse(response);
			return response;
			}
		);
	}

	@Override
	public GradeResponse grade(GradeRequest request) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			int currentAttempt = attempt;
			try {
				return executeAttempt(
					new AiCallContext(
						GRADE_PATH,
						currentAttempt,
						currentAttempt > 1,
						null,
						null,
						request.quizId()
					),
					() -> {
						GradeResponse response = gradeRestClient.post()
							.uri(GRADE_PATH)
							.contentType(MediaType.APPLICATION_JSON)
							.body(request)
							.retrieve()
							.body(GradeResponse.class);
						if (response == null
							|| !SCHEMA_VERSION.equals(
								response.schemaVersion()
							)) {
							throw new AiClientException(
								ErrorCode.AI_RESPONSE_INVALID
							);
						}
						return response;
					}
				);
			} catch (AiClientException exception) {
				if (attempt == 1 && exception.retryable()) {
					continue;
				}
				throw exception;
			}
		}
		throw new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	@Override
	public QuizAssessmentResponse quizAssessment(
		QuizAssessmentRequest request
	) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			int currentAttempt = attempt;
			try {
				return executeAttempt(
					new AiCallContext(
						QUIZ_ASSESSMENT_PATH,
						currentAttempt,
						currentAttempt > 1,
						null,
						null,
						request.quizResult().quizId()
					),
					() -> {
						QuizAssessmentResponse response =
							pipelineRestClient.post()
								.uri(QUIZ_ASSESSMENT_PATH)
								.contentType(MediaType.APPLICATION_JSON)
								.body(request)
								.retrieve()
								.body(QuizAssessmentResponse.class);
						validateQuizAssessmentResponse(response);
						return response;
					}
				);
			} catch (AiClientException exception) {
				if (attempt == 1 && exception.retryable()) {
					continue;
				}
				throw exception;
			}
		}
		throw new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	@Override
	public DiagnosisResponse diagnosis(DiagnosisRequest request) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			int currentAttempt = attempt;
			try {
				return executeAttempt(
					new AiCallContext(
						DIAGNOSIS_PATH,
						currentAttempt,
						currentAttempt > 1,
						null,
						null,
						request.quizResult().quizId()
					),
					() -> {
						DiagnosisResponse response = pipelineRestClient.post()
							.uri(DIAGNOSIS_PATH)
							.contentType(MediaType.APPLICATION_JSON)
							.body(request)
							.retrieve()
							.body(DiagnosisResponse.class);
						validateDiagnosisResponse(response);
						return response;
					}
				);
			} catch (AiClientException exception) {
				if (attempt == 1 && exception.retryable()) {
					continue;
				}
				throw exception;
			}
		}
		throw new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	private <T> T executeAttempt(
		AiCallContext context,
		Supplier<T> operation
	) {
		long startedAt = System.nanoTime();
		try {
			T response = operation.get();
			logSuccess(context, elapsedMillis(startedAt), response);
			return response;
		} catch (AiClientException exception) {
			logFailure(context, elapsedMillis(startedAt), exception);
			throw exception;
		} catch (ResourceAccessException exception) {
			AiClientException mapped = mapResourceFailure(exception);
			logFailure(context, elapsedMillis(startedAt), mapped);
			throw mapped;
		} catch (RestClientResponseException exception) {
			AiClientException mapped = mapErrorResponse(exception);
			logFailure(context, elapsedMillis(startedAt), mapped);
			throw mapped;
		} catch (RestClientException exception) {
			AiClientException mapped = isTimeoutFailure(exception)
				? new AiClientException(
					ErrorCode.AI_SERVICE_TIMEOUT,
					exception
				)
				: new AiClientException(
					ErrorCode.AI_RESPONSE_INVALID,
					exception
				);
			logFailure(context, elapsedMillis(startedAt), mapped);
			throw mapped;
		}
	}

	private void logSuccess(
		AiCallContext context,
		long durationMs,
		Object response
	) {
		LoggingEventBuilder builder = withContext(
			log.atInfo(),
			context,
			durationMs
		).addKeyValue("status", "SUCCESS");
		if (response instanceof TurnResponse turnResponse) {
			builder.addKeyValue(
				"adjustments",
				turnAdjustments(turnResponse)
			);
		}
		builder.log("AI service call completed");
	}

	private List<Map<String, Object>> turnAdjustments(
		TurnResponse response
	) {
		List<Map<String, Object>> values = new ArrayList<>();
		for (ActionExecuted action : response.actionsExecuted()) {
			for (Adjustment adjustment : action.adjustments()) {
				Map<String, Object> value = new LinkedHashMap<>();
				value.put("actionId", action.actionId());
				value.put("field", adjustment.field());
				value.put("from", adjustment.from());
				value.put("to", adjustment.to());
				value.put("reason", adjustment.reason());
				values.add(value);
			}
		}
		return List.copyOf(values);
	}

	private void logFailure(
		AiCallContext context,
		long durationMs,
		AiClientException exception
	) {
		LoggingEventBuilder builder = exception.category()
			== AiFailureCategory.AUTH
			? log.atError()
			: log.atWarn();
		withContext(builder, context, durationMs)
			.addKeyValue("status", "FAILED")
			.addKeyValue("category", exception.category())
			.addKeyValue("errorCode", exception.errorCode().code())
			.log("AI service call failed");
	}

	private LoggingEventBuilder withContext(
		LoggingEventBuilder builder,
		AiCallContext context,
		long durationMs
	) {
		builder
			.addKeyValue("endpoint", context.endpoint())
			.addKeyValue("durationMs", durationMs)
			.addKeyValue("attempt", context.attempt())
			.addKeyValue("retried", context.retried());
		if (context.turnId() != null) {
			builder.addKeyValue("turnId", context.turnId());
		}
		if (context.sessionId() != null) {
			builder.addKeyValue("sessionId", context.sessionId());
		}
		if (context.quizId() != null) {
			builder.addKeyValue("quizId", context.quizId());
		}
		return builder;
	}

	private long elapsedMillis(long startedAt) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
	}

	private Long sessionId(TurnRequest request) {
		Object sessionId = request.session() == null
			? null
			: request.session().get("sessionId");
		return sessionId instanceof Number number ? number.longValue() : null;
	}

	private void validateTurnResponse(TurnResponse response, TurnRequest request) {
		if (response == null
			|| !StringUtils.hasText(response.schemaVersion())
			|| !StringUtils.hasText(response.turnId())
			|| !StringUtils.hasText(response.turnGoal())
			|| response.actionsExecuted() == null
			|| response.messages() == null
			|| response.statePatch() == null
			|| response.uiActions() == null
			|| response.memoryCandidates() == null
			|| !response.schemaVersion().equals(request.schemaVersion())
			|| !response.turnId().equals(request.turnId())) {
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
		}
		for (ActionExecuted action : response.actionsExecuted()) {
			if (action == null
				|| !StringUtils.hasText(action.actionId())
				|| !StringUtils.hasText(action.agent())
				|| !Set.of("SUCCESS", "FAILED", "SKIPPED")
					.contains(action.status())) {
				throw new AiClientException(
					ErrorCode.AI_RESPONSE_INVALID
				);
			}
			for (Adjustment adjustment : action.adjustments()) {
				if (adjustment == null
					|| !StringUtils.hasText(adjustment.field())
					|| !StringUtils.hasText(adjustment.reason())) {
					throw new AiClientException(
						ErrorCode.AI_RESPONSE_INVALID
					);
				}
			}
		}
	}

	private void validateExtractResponse(ExtractResponse response) {
		if (response == null
			|| !SCHEMA_VERSION.equals(response.schemaVersion())
			|| response.pageCount() < 1
			|| response.pages() == null
			|| response.pages().size() != response.pageCount()) {
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
		}

		for (int index = 0; index < response.pages().size(); index++) {
			ExtractedPage page = response.pages().get(index);
			if (page == null
				|| page.pageNumber() != index + 1
				|| page.text() == null) {
				throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
			}
		}
	}

	private void validateQuizAssessmentResponse(
		QuizAssessmentResponse response
	) {
		if (response == null
			|| !SCHEMA_VERSION.equals(response.schemaVersion())
			|| !StringUtils.hasText(response.understandingSummary())
			|| !validTextList(response.strengths())
			|| !validTextList(response.weaknesses())
			|| !validTextList(response.suspectedMisconceptions())
			|| !StringUtils.hasText(response.recommendedNextDirection())
			|| response.memoryCandidates() == null
			|| !validTextList(response.evidence())) {
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
		}
		for (QuizAssessmentResponse.MemoryCandidate candidate
			: response.memoryCandidates()) {
			if (candidate == null
				|| !StringUtils.hasText(candidate.type())
				|| !StringUtils.hasText(candidate.content())
				|| candidate.confidence() == null
				|| candidate.confidence().signum() < 0
				|| candidate.confidence().compareTo(
					java.math.BigDecimal.ONE
				) > 0) {
				throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
			}
		}
	}

	private void validateDiagnosisResponse(DiagnosisResponse response) {
		if (response == null
			|| !SCHEMA_VERSION.equals(response.schemaVersion())
			|| !validTextList(response.focusConcepts())
			|| !validTextList(response.suspectedMisconceptions())
			|| !StringUtils.hasText(response.diagnosticPrompt())
			|| !validTextList(response.evidence())
			|| !StringUtils.hasText(response.repairHint())) {
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
		}
	}

	private boolean validTextList(java.util.List<String> values) {
		return values != null
			&& values.stream().allMatch(StringUtils::hasText);
	}

	private AiClientException mapResourceFailure(ResourceAccessException exception) {
		if (hasCause(exception, HttpConnectTimeoutException.class)
			|| hasCause(exception, ConnectException.class)) {
			return new AiClientException(
				ErrorCode.AI_SERVICE_UNAVAILABLE,
				AiFailureCategory.INTERNAL,
				false,
				exception
			);
		}
		if (hasCause(exception, HttpTimeoutException.class)
			|| hasCause(exception, SocketTimeoutException.class)) {
			return new AiClientException(
				ErrorCode.AI_SERVICE_TIMEOUT,
				AiFailureCategory.TIMEOUT,
				false,
				exception
			);
		}
		return new AiClientException(
			ErrorCode.AI_SERVICE_UNAVAILABLE,
			AiFailureCategory.INTERNAL,
			false,
			exception
		);
	}

	private AiClientException mapErrorResponse(RestClientResponseException exception) {
		AiErrorResponse response;
		try {
			response = exception.getResponseBodyAs(AiErrorResponse.class);
		} catch (RestClientException parsingFailure) {
			return fallbackForStatus(exception, parsingFailure);
		}

		if (response == null || response.error() == null
			|| response.error().category() == null) {
			return fallbackForStatus(exception, exception);
		}

		ErrorCode errorCode = switch (response.error().category()) {
			case TIMEOUT -> ErrorCode.AI_SERVICE_TIMEOUT;
			case SCHEMA -> ErrorCode.AI_RESPONSE_INVALID;
			case POLICY -> ErrorCode.AI_POLICY_REJECTED;
			case INTERNAL -> ErrorCode.AI_SERVICE_UNAVAILABLE;
			case AUTH -> ErrorCode.INTERNAL_SERVER_ERROR;
		};

		boolean retryable = response.error().retryable()
			&& (response.error().category() == AiErrorResponse.Category.TIMEOUT
				|| response.error().category() == AiErrorResponse.Category.INTERNAL);
		return new AiClientException(
			errorCode,
			AiFailureCategory.valueOf(response.error().category().name()),
			retryable,
			response.error().code(),
			exception
		);
	}

	private AiClientException fallbackForStatus(
		RestClientResponseException exception,
		Throwable cause
	) {
		if (exception.getStatusCode().value() == 401
			|| exception.getStatusCode().value() == 403) {
			return new AiClientException(
				ErrorCode.INTERNAL_SERVER_ERROR,
				AiFailureCategory.AUTH,
				false,
				cause
			);
		}
		return new AiClientException(
			ErrorCode.AI_SERVICE_UNAVAILABLE,
			AiFailureCategory.INTERNAL,
			false,
			cause
		);
	}

	private boolean hasCause(Throwable throwable, Class<? extends Throwable> causeType) {
		Throwable current = throwable;
		while (current != null) {
			if (causeType.isInstance(current)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private boolean isTimeoutFailure(Throwable throwable) {
		return hasCause(throwable, HttpTimeoutException.class)
			|| hasCause(throwable, SocketTimeoutException.class);
	}

	private record AiCallContext(
		String endpoint,
		int attempt,
		boolean retried,
		String turnId,
		Long sessionId,
		Long quizId
	) {
	}
}
