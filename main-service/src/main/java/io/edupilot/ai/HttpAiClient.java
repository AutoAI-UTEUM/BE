package io.edupilot.ai;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

@Component
public class HttpAiClient implements AiClient {

	private static final Logger log = LoggerFactory.getLogger(HttpAiClient.class);
	private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
	private static final String TRACE_ID_HEADER = "X-Trace-Id";
	private static final String TURN_PATH = "/internal/ai/turn";
	private static final String EXTRACT_PATH = "/internal/ai/extract";
	private static final String GRADE_PATH = "/internal/ai/grade";
	private static final String QUIZ_ASSESSMENT_PATH =
		"/internal/ai/quiz-assessment";
	private static final String DIAGNOSIS_PATH = "/internal/ai/diagnosis";
	private static final String SCHEMA_VERSION = "1.0";

	private final RestClient restClient;
	private final RestClient extractRestClient;
	private final RestClient gradeRestClient;
	private final RestClient pipelineRestClient;
	private final String healthPath;

	public HttpAiClient(AiClientProperties properties) {
		// TODO ai-integration-contract v0.3에서 예산 확정 전까지 비멱등 turn 호출은 재시도하지 않는다.
		this.restClient = buildRestClient(properties, properties.readTimeout());
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
	}

	private RestClient buildRestClient(
		AiClientProperties properties,
		java.time.Duration readTimeout
	) {
		SimpleClientHttpRequestFactory requestFactory =
			new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(readTimeout);

		return RestClient.builder()
			.baseUrl(properties.baseUrl().toString())
			.requestFactory(requestFactory)
			.requestInterceptor((request, body, execution) -> {
				request.getHeaders().set(INTERNAL_TOKEN_HEADER, properties.internalToken());
				String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
				if (StringUtils.hasText(traceId)) {
					request.getHeaders().set(TRACE_ID_HEADER, traceId);
				}
				return execution.execute(request, body);
			})
			.build();
	}

	@Override
	public AiHealthResponse health() {
		try {
			AiHealthResponse response = restClient.get()
				.uri(healthPath)
				.retrieve()
				.body(AiHealthResponse.class);
			if (response == null || !StringUtils.hasText(response.status())) {
				throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
			}
			return response;
		} catch (AiClientException exception) {
			throw exception;
		} catch (ResourceAccessException exception) {
			throw mapResourceFailure(exception);
		} catch (RestClientResponseException exception) {
			throw mapErrorResponse(exception);
		} catch (RestClientException exception) {
			if (isTimeoutFailure(exception)) {
				throw new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT, exception);
			}
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID, exception);
		}
	}

	@Override
	public TurnResponse executeTurn(TurnRequest request) {
		try {
			TurnResponse response = restClient.post()
				.uri(TURN_PATH)
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(TurnResponse.class);
			validateTurnResponse(response, request);
			return response;
		} catch (AiClientException exception) {
			throw exception;
		} catch (ResourceAccessException exception) {
			throw mapResourceFailure(exception);
		} catch (RestClientResponseException exception) {
			throw mapErrorResponse(exception);
		} catch (RestClientException exception) {
			if (isTimeoutFailure(exception)) {
				throw new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT, exception);
			}
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID, exception);
		}
	}

	@Override
	public ExtractResponse extract(Resource pdfResource) {
		try {
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
		} catch (AiClientException exception) {
			throw exception;
		} catch (ResourceAccessException exception) {
			throw mapResourceFailure(exception);
		} catch (RestClientResponseException exception) {
			throw mapErrorResponse(exception);
		} catch (RestClientException exception) {
			if (isTimeoutFailure(exception)) {
				throw new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT, exception);
			}
			throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID, exception);
		}
	}

	@Override
	public GradeResponse grade(GradeRequest request) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				GradeResponse response = gradeRestClient.post()
					.uri(GRADE_PATH)
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.body(GradeResponse.class);
				if (response == null
					|| !SCHEMA_VERSION.equals(response.schemaVersion())) {
					throw new AiClientException(ErrorCode.AI_RESPONSE_INVALID);
				}
				return response;
			} catch (AiClientException exception) {
				if (attempt == 0 && exception.retryable()) {
					continue;
				}
				throw exception;
			} catch (ResourceAccessException exception) {
				throw mapResourceFailure(exception);
			} catch (RestClientResponseException exception) {
				AiClientException mapped = mapErrorResponse(exception);
				if (attempt == 0 && mapped.retryable()) {
					continue;
				}
				throw mapped;
			} catch (RestClientException exception) {
				if (isTimeoutFailure(exception)) {
					throw new AiClientException(
						ErrorCode.AI_SERVICE_TIMEOUT,
						exception
					);
				}
				throw new AiClientException(
					ErrorCode.AI_RESPONSE_INVALID,
					exception
				);
			}
		}
		throw new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	@Override
	public QuizAssessmentResponse quizAssessment(
		QuizAssessmentRequest request
	) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				QuizAssessmentResponse response = pipelineRestClient.post()
					.uri(QUIZ_ASSESSMENT_PATH)
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.body(QuizAssessmentResponse.class);
				validateQuizAssessmentResponse(response);
				return response;
			} catch (AiClientException exception) {
				if (attempt == 0 && exception.retryable()) {
					continue;
				}
				throw exception;
			} catch (ResourceAccessException exception) {
				throw mapResourceFailure(exception);
			} catch (RestClientResponseException exception) {
				AiClientException mapped = mapErrorResponse(exception);
				if (attempt == 0 && mapped.retryable()) {
					continue;
				}
				throw mapped;
			} catch (RestClientException exception) {
				if (isTimeoutFailure(exception)) {
					throw new AiClientException(
						ErrorCode.AI_SERVICE_TIMEOUT,
						exception
					);
				}
				throw new AiClientException(
					ErrorCode.AI_RESPONSE_INVALID,
					exception
				);
			}
		}
		throw new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	@Override
	public DiagnosisResponse diagnosis(DiagnosisRequest request) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				DiagnosisResponse response = pipelineRestClient.post()
					.uri(DIAGNOSIS_PATH)
					.contentType(MediaType.APPLICATION_JSON)
					.body(request)
					.retrieve()
					.body(DiagnosisResponse.class);
				validateDiagnosisResponse(response);
				return response;
			} catch (AiClientException exception) {
				if (attempt == 0 && exception.retryable()) {
					continue;
				}
				throw exception;
			} catch (ResourceAccessException exception) {
				throw mapResourceFailure(exception);
			} catch (RestClientResponseException exception) {
				AiClientException mapped = mapErrorResponse(exception);
				if (attempt == 0 && mapped.retryable()) {
					continue;
				}
				throw mapped;
			} catch (RestClientException exception) {
				if (isTimeoutFailure(exception)) {
					throw new AiClientException(
						ErrorCode.AI_SERVICE_TIMEOUT,
						exception
					);
				}
				throw new AiClientException(
					ErrorCode.AI_RESPONSE_INVALID,
					exception
				);
			}
		}
		throw new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE);
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
			return new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE, exception);
		}
		if (hasCause(exception, HttpTimeoutException.class)
			|| hasCause(exception, SocketTimeoutException.class)) {
			return new AiClientException(ErrorCode.AI_SERVICE_TIMEOUT, exception);
		}
		return new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE, exception);
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

		if (response.error().category() == AiErrorResponse.Category.AUTH) {
			log.error("AI internal authentication failed: status={}, errorCode={}",
				exception.getStatusCode().value(), errorCode.code());
		}
		boolean retryable = response.error().retryable()
			&& (response.error().category() == AiErrorResponse.Category.TIMEOUT
				|| response.error().category() == AiErrorResponse.Category.INTERNAL);
		return new AiClientException(errorCode, retryable, exception);
	}

	private AiClientException fallbackForStatus(
		RestClientResponseException exception,
		Throwable cause
	) {
		if (exception.getStatusCode().value() == 401
			|| exception.getStatusCode().value() == 403) {
			log.error("AI internal authentication response was invalid: status={}, errorCode={}",
				exception.getStatusCode().value(), ErrorCode.INTERNAL_SERVER_ERROR.code());
			return new AiClientException(ErrorCode.INTERNAL_SERVER_ERROR, cause);
		}
		return new AiClientException(ErrorCode.AI_SERVICE_UNAVAILABLE, cause);
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
}
