package io.edupilot.session;

import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.session.dto.TurnRequest;
import io.edupilot.session.dto.TurnResponse;
import tools.jackson.databind.JsonNode;

@Service
public class SessionTurnService {

	private static final Set<String> QUIZ_TYPES = Set.of(
		"MCQ",
		"OX",
		"SHORT",
		"ESSAY"
	);

	private final TurnClaimService claimService;
	private final TurnPersistenceService persistenceService;

	public SessionTurnService(
		TurnClaimService claimService,
		TurnPersistenceService persistenceService
	) {
		this.claimService = claimService;
		this.persistenceService = persistenceService;
	}

	public TurnResponse execute(
		Long userId,
		Long sessionId,
		TurnRequest request
	) {
		TurnEventType eventType = parseEventType(request.eventType());
		String userContent = validatePayload(eventType, request.payload());
		claimService.claim(userId, sessionId, request.requestId());
		try {
			return persistenceService.persist(
				userId,
				sessionId,
				request.requestId(),
				userContent
			);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.TURN_ALREADY_PROCESSED);
		} finally {
			claimService.release(sessionId, request.requestId());
		}
	}

	private TurnEventType parseEventType(String value) {
		try {
			return TurnEventType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_EVENT_TYPE);
		}
	}

	private String validatePayload(TurnEventType eventType, JsonNode payload) {
		if (!payload.isObject()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return switch (eventType) {
			case EXPLAIN_CURRENT_PAGE -> {
				String detailLevel = requiredText(payload, "detailLevel");
				yield "현재 페이지 설명 요청: " + detailLevel;
			}
			case USER_QUESTION -> requiredText(payload, "message");
			case QUIZ_TYPE_SELECTED -> {
				String quizType = requiredText(payload, "quizType");
				if (!QUIZ_TYPES.contains(quizType)) {
					throw new BusinessException(ErrorCode.VALIDATION_FAILED);
				}
				// TODO Epic 6: 실제 퀴즈 생성과 세션 activeQuizId 전제를 검증한다.
				yield "퀴즈 유형 선택: " + quizType;
			}
			case DIAGNOSIS_ANSWER_SUBMITTED -> {
				JsonNode diagnosisId = payload.get("diagnosisId");
				if (diagnosisId == null
					|| !diagnosisId.canConvertToLong()
					|| diagnosisId.longValue() < 1) {
					throw new BusinessException(ErrorCode.VALIDATION_FAILED);
				}
				String answer = requiredText(payload, "answer");
				// TODO Epic 7: pending diagnosis 소유권과 답변 대기 상태를 검증한다.
				yield answer;
			}
		};
	}

	private String requiredText(JsonNode payload, String field) {
		JsonNode value = payload.get(field);
		if (value == null || !value.isTextual() || value.textValue().isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return value.textValue().trim();
	}
}
