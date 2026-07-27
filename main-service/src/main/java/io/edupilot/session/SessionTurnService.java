package io.edupilot.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.memory.LearnerMemoryPromotionService;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import tools.jackson.databind.JsonNode;

@Service
public class SessionTurnService {

	private static final Logger log =
		LoggerFactory.getLogger(SessionTurnService.class);
	private static final String SCHEMA_VERSION = "1.0";
	private static final Set<String> QUIZ_TYPES = Set.of(
		"MCQ",
		"OX",
		"SHORT",
		"ESSAY"
	);

	private final TurnClaimService claimService;
	private final TurnPreparationService preparationService;
	private final TurnSnapshotService snapshotService;
	private final AiClient aiClient;
	private final TurnResponseValidator responseValidator;
	private final TurnPersistenceService persistenceService;
	private final LearnerMemoryPromotionService memoryPromotionService;

	public SessionTurnService(
		TurnClaimService claimService,
		TurnPreparationService preparationService,
		TurnSnapshotService snapshotService,
		AiClient aiClient,
		TurnResponseValidator responseValidator,
		TurnPersistenceService persistenceService,
		LearnerMemoryPromotionService memoryPromotionService
	) {
		this.claimService = claimService;
		this.preparationService = preparationService;
		this.snapshotService = snapshotService;
		this.aiClient = aiClient;
		this.responseValidator = responseValidator;
		this.persistenceService = persistenceService;
		this.memoryPromotionService = memoryPromotionService;
	}

	public TurnResponse execute(
		Long userId,
		Long sessionId,
		TurnRequest request
	) {
		TurnEventType eventType = parseEventType(request.eventType());
		ValidatedPayload payload = validatePayload(
			eventType,
			request.payload()
		);
		claimService.claim(userId, sessionId, request.requestId());
		try {
			PreparedTurn prepared;
			try {
				prepared = preparationService.prepare(
					userId,
					sessionId,
					request.requestId(),
					payload.userContent(),
					payload.diagnosisId()
				);
			} catch (DataIntegrityViolationException exception) {
				throw new BusinessException(
					ErrorCode.TURN_ALREADY_PROCESSED
				);
			}
			TurnSnapshot snapshot = snapshotService.build(
				userId,
				sessionId,
				prepared.userMessageId()
			);
			io.edupilot.ai.dto.TurnResponse aiResponse = executeAiTurn(
				request,
				eventType,
				snapshot
			);
			PersistedTurn persisted = persistenceService.persist(
				userId,
				sessionId,
				request.requestId(),
				eventType,
				payload.diagnosisId(),
				prepared.userMessageId(),
				aiResponse
			);
			promoteMemory(userId, persisted);
			return persisted.response();
		} finally {
			claimService.release(sessionId, request.requestId());
		}
	}

	private io.edupilot.ai.dto.TurnResponse executeAiTurn(
		TurnRequest request,
		TurnEventType eventType,
		TurnSnapshot snapshot
	) {
		for (int attempt = 1; attempt <= 2; attempt++) {
			String turnId = "turn-" + UUID.randomUUID();
			io.edupilot.ai.dto.TurnRequest aiRequest =
				new io.edupilot.ai.dto.TurnRequest(
					SCHEMA_VERSION,
					turnId,
					snapshot.session(),
					eventData(eventType, request.payload()),
					snapshot.context()
				);
			try {
				io.edupilot.ai.dto.TurnResponse response =
					aiClient.executeTurn(aiRequest);
				responseValidator.validate(response, turnId);
				return response;
			} catch (AiClientException exception) {
				log.warn(
					"AI turn attempt failed: requestId={}, traceId={}, attempt={}, turnId={}, errorCode={}, retryable={}",
					request.requestId(),
					MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
					attempt,
					turnId,
					exception.errorCode().code(),
					exception.retryable()
				);
				if (attempt == 1 && exception.retryable()) {
					continue;
				}
				throw exception;
			}
		}
		throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE);
	}

	private Map<String, Object> eventData(
		TurnEventType eventType,
		JsonNode payload
	) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventType", eventType.name());
		event.put("payload", payload);
		return event;
	}

	private void promoteMemory(Long userId, PersistedTurn persisted) {
		if (persisted.memoryWrite() == null) {
			return;
		}
		try {
			memoryPromotionService.promoteMemory(
				userId,
				persisted.materialId(),
				persisted.memoryWrite()
			);
		} catch (RuntimeException exception) {
			log.warn(
				"Learner memory promotion failed after turn commit: userId={}, materialId={}, errorType={}",
				userId,
				persisted.materialId(),
				exception.getClass().getSimpleName()
			);
		}
	}

	private TurnEventType parseEventType(String value) {
		try {
			return TurnEventType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_EVENT_TYPE);
		}
	}

	private ValidatedPayload validatePayload(
		TurnEventType eventType,
		JsonNode payload
	) {
		if (!payload.isObject()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return switch (eventType) {
			case EXPLAIN_CURRENT_PAGE -> {
				String detailLevel = requiredText(payload, "detailLevel");
				if (!Set.of("NORMAL", "DETAILED").contains(detailLevel)) {
					throw new BusinessException(
						ErrorCode.VALIDATION_FAILED
					);
				}
				yield new ValidatedPayload(
					"현재 페이지 설명 요청: " + detailLevel,
					null
				);
			}
			case USER_QUESTION -> new ValidatedPayload(
				requiredText(payload, "message"),
				null
			);
			case QUIZ_TYPE_SELECTED -> {
				String quizType = requiredText(payload, "quizType");
				if (!QUIZ_TYPES.contains(quizType)) {
					throw new BusinessException(
						ErrorCode.VALIDATION_FAILED
					);
				}
				yield new ValidatedPayload(
					"퀴즈 유형 선택: " + quizType,
					null
				);
			}
			case DIAGNOSIS_ANSWER_SUBMITTED -> {
				JsonNode diagnosisId = payload.get("diagnosisId");
				if (diagnosisId == null
					|| !diagnosisId.canConvertToLong()
					|| diagnosisId.longValue() < 1) {
					throw new BusinessException(
						ErrorCode.VALIDATION_FAILED
					);
				}
				yield new ValidatedPayload(
					requiredText(payload, "answer"),
					diagnosisId.longValue()
				);
			}
		};
	}

	private String requiredText(JsonNode payload, String field) {
		JsonNode value = payload.get(field);
		if (value == null
			|| !value.isTextual()
			|| value.textValue().isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return value.textValue().trim();
	}

	private record ValidatedPayload(
		String userContent,
		Long diagnosisId
	) {
	}
}
