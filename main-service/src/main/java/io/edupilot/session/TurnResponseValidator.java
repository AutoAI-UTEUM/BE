package io.edupilot.session;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.edupilot.ai.dto.TurnResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.quiz.QuizGenerationValidator;

@Component
public class TurnResponseValidator {

	private static final Logger log =
		LoggerFactory.getLogger(TurnResponseValidator.class);
	private static final Set<String> PATCH_FIELDS = Set.of(
		"pageStatus",
		"activeQuizId",
		"pendingDiagnosis",
		"qaThread"
	);
	private static final Set<String> PAGE_STATUSES = Set.of(
		"EXPLAINING",
		"EXPLAINED",
		"QUIZ_READY",
		"DIAGNOSIS_PENDING",
		"REPAIR_COMPLETED"
	);
	private static final Set<String> MESSAGE_TYPES = Set.of(
		"EXPLANATION",
		"QA",
		"DIAGNOSIS",
		"REPAIR",
		"SYSTEM"
	);
	private static final Set<String> MEMORY_CANDIDATE_FIELDS = Set.of(
		"type",
		"content",
		"confidence",
		"evidence",
		"promotionRequested"
	);
	private static final Set<String> MEMORY_CANDIDATE_TYPES = Set.of(
		"STRENGTH",
		"WEAKNESS",
		"MISCONCEPTION",
		"PREFERENCE"
	);
	private final QuizGenerationValidator quizGenerationValidator;

	public TurnResponseValidator(
		QuizGenerationValidator quizGenerationValidator
	) {
		this.quizGenerationValidator = quizGenerationValidator;
	}

	public void validate(TurnResponse response, String expectedTurnId) {
		validate(
			response,
			expectedTurnId,
			null,
			null,
			null,
			Set.of()
		);
	}

	public void validate(
		TurnResponse response,
		String expectedTurnId,
		String expectedQaThreadRef
	) {
		validate(
			response,
			expectedTurnId,
			expectedQaThreadRef,
			null,
			null,
			Set.of()
		);
	}

	public void validate(
		TurnResponse response,
		String expectedTurnId,
		String expectedQaThreadRef,
		TurnEventType eventType,
		String expectedQuizType,
		Set<Integer> availableQuizPages
	) {
		if (response == null) {
			throw invalid("turn response is null");
		}
		if (!expectedTurnId.equals(response.turnId())) {
			throw invalid("turnId mismatch");
		}
		if (response.messages() == null) {
			throw invalid("messages must not be null");
		}
		if (response.statePatch() == null) {
			throw invalid("statePatch must not be null");
		}
		if (response.uiActions() == null) {
			throw invalid("uiActions must not be null");
		}
		if (response.memoryCandidates() == null) {
			throw invalid("memoryCandidates must not be null");
		}
		validateMessages(response);
		validateStatePatch(response.statePatch(), expectedQaThreadRef);
		validateQuiz(
			response,
			eventType,
			expectedQuizType,
			availableQuizPages
		);
		warnIgnoredUiActions(
			response.turnId(),
			response.uiActions().stream()
				.filter(action -> !isMoveNextPageProposal(action))
				.toList()
		);
		warnIgnoredActiveQuizId(response);
		validateMemoryCandidates(response);
	}

	private void validateMessages(TurnResponse response) {
		for (int index = 0; index < response.messages().size(); index++) {
			Map<String, Object> message = response.messages().get(index);
			if (message == null) {
				throw invalid(
					"messages[%d] must not be null".formatted(index)
				);
			}
			if (!MESSAGE_TYPES.contains(text(message, "messageType"))) {
				throw invalid(
					"messages[%d].messageType is unsupported"
						.formatted(index)
				);
			}
			if (!StringUtils.hasText(text(message, "content"))) {
				throw invalid(
					"messages[%d].content must not be blank"
						.formatted(index)
				);
			}
		}
	}

	private void validateStatePatch(
		Map<String, Object> patch,
		String expectedQaThreadRef
	) {
		if (!PATCH_FIELDS.containsAll(patch.keySet())) {
			throw policy("statePatch contains unsupported field");
		}
		if (patch.containsKey("pageStatus")
			&& !PAGE_STATUSES.contains(text(patch, "pageStatus"))) {
			throw policy("statePatch.pageStatus is unsupported");
		}
		validateNullablePositiveLong(patch, "pendingDiagnosis");
		if (!patch.containsKey("qaThread")) {
			return;
		}
		Object raw = patch.get("qaThread");
		if (!(raw instanceof Map<?, ?> qaThread)
			|| qaThread.keySet().stream()
				.anyMatch(key -> !(key instanceof String))) {
			throw policy(
				"statePatch.qaThread must be an object with string keys"
			);
		}
		Set<String> keys = qaThread.keySet().stream()
			.map(String.class::cast)
			.collect(java.util.stream.Collectors.toSet());
		String mode = valueText(qaThread.get("mode"));
		String threadRef = valueText(qaThread.get("threadRef"));
		if ("START_NEW".equals(mode)) {
			if (!keys.equals(Set.of("mode"))) {
				throw policy(
					"statePatch.qaThread START_NEW fields mismatch"
				);
			}
			return;
		}
		if (!"FOLLOW_UP".equals(mode)) {
			throw policy("statePatch.qaThread.mode is unsupported");
		}
		if (!keys.equals(Set.of("mode", "threadRef"))) {
			throw policy(
				"statePatch.qaThread FOLLOW_UP fields mismatch"
			);
		}
		if (threadRef == null
			|| !threadRef.matches("qa-[1-9][0-9]*")) {
			throw policy(
				"statePatch.qaThread.threadRef format is invalid"
			);
		}
		if (!threadRef.equals(expectedQaThreadRef)) {
			throw policy(
				"statePatch.qaThread.threadRef does not match snapshot"
			);
		}
	}

	static boolean isMoveNextPageProposal(Map<String, Object> action) {
		return action != null
			&& "BINARY_DECISION".equals(action.get("type"))
			&& "MOVE_NEXT_PAGE".equals(action.get("yesEvent"))
			&& "WAIT".equals(action.get("noEvent"));
	}

	static void warnIgnoredUiActions(
		String turnId,
		List<Map<String, Object>> uiActions
	) {
		if (uiActions.isEmpty()) {
			return;
		}
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("turnId", turnId)
			.addKeyValue("uiActionCount", uiActions.size())
			.log("Ignored non-empty AI uiActions");
	}

	static void warnMoveNextPageDroppedAtLastPage(
		String turnId,
		int currentPage,
		Integer pageCount,
		int uiActionCount
	) {
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("turnId", turnId)
			.addKeyValue("currentPage", currentPage)
			.addKeyValue("pageCount", pageCount)
			.addKeyValue("uiActionCount", uiActionCount)
			.addKeyValue("reason", "last page")
			.log("Dropped AI moveNextPage uiAction at last page");
	}

	private void validateQuiz(
		TurnResponse response,
		TurnEventType eventType,
		String expectedQuizType,
		Set<Integer> availableQuizPages
	) {
		boolean quizTurn = eventType == TurnEventType.QUIZ_TYPE_SELECTED;
		if (quizTurn != (response.quiz() != null)) {
			throw invalid(
				"quiz presence mismatch: expected=%s actual=%s"
					.formatted(quizTurn, response.quiz() != null)
			);
		}
		if (!quizTurn) {
			return;
		}
		quizGenerationValidator.validate(
			response.quiz(),
			response.schemaVersion(),
			expectedQuizType,
			availableQuizPages
		);
	}

	private void warnIgnoredActiveQuizId(TurnResponse response) {
		if (!response.statePatch().containsKey("activeQuizId")) {
			return;
		}
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("turnId", response.turnId())
			.log("Ignored AI activeQuizId statePatch");
	}

	private void validateMemoryCandidates(TurnResponse response) {
		for (int index = 0;
			index < response.memoryCandidates().size();
			index++) {
			Map<String, Object> candidate =
				response.memoryCandidates().get(index);
			if (candidate == null) {
				throw invalid(
					"memoryCandidates[%d] must not be null"
						.formatted(index)
				);
			}
			if (!candidate.keySet().equals(MEMORY_CANDIDATE_FIELDS)) {
				throw invalid(
					"memoryCandidates[%d] fields mismatch"
						.formatted(index)
				);
			}
			if (!MEMORY_CANDIDATE_TYPES.contains(
				text(candidate, "type")
			)) {
				throw invalid(
					"memoryCandidates[%d].type is unsupported"
						.formatted(index)
				);
			}
			if (!StringUtils.hasText(text(candidate, "content"))) {
				throw invalid(
					"memoryCandidates[%d].content must not be blank"
						.formatted(index)
				);
			}
			if (!(candidate.get("promotionRequested") instanceof Boolean)) {
				throw invalid(
					"memoryCandidates[%d].promotionRequested must be boolean"
						.formatted(index)
				);
			}
			BigDecimal confidence = decimal(candidate.get("confidence"));
			if (confidence == null) {
				throw invalid(
					"memoryCandidates[%d].confidence must be numeric"
						.formatted(index)
				);
			}
			if (confidence.compareTo(BigDecimal.ZERO) < 0
				|| confidence.compareTo(BigDecimal.ONE) > 0) {
				throw invalid(
					"memoryCandidates[%d].confidence must be between 0 and 1"
						.formatted(index)
				);
			}
			validateMemoryEvidence(candidate.get("evidence"), index);
		}
	}

	private void validateMemoryEvidence(Object value, int candidateIndex) {
		if (!(value instanceof List<?> evidence)) {
			throw invalid(
				"memoryCandidates[%d].evidence must be an array"
					.formatted(candidateIndex)
			);
		}
		if (evidence.isEmpty()) {
			throw invalid(
				"memoryCandidates[%d].evidence must not be empty"
					.formatted(candidateIndex)
			);
		}
		Set<String> unique = new HashSet<>();
		for (int index = 0; index < evidence.size(); index++) {
			Object item = evidence.get(index);
			if (!(item instanceof String text)) {
				throw invalid(
					"memoryCandidates[%d].evidence[%d] must be text"
						.formatted(candidateIndex, index)
				);
			}
			if (!StringUtils.hasText(text)) {
				throw invalid(
					"memoryCandidates[%d].evidence[%d] must not be blank"
						.formatted(candidateIndex, index)
				);
			}
			if (!unique.add(text.trim())) {
				throw invalid(
					"memoryCandidates[%d].evidence contains duplicate item"
						.formatted(candidateIndex)
				);
			}
		}
	}

	private void validateNullablePositiveLong(
		Map<String, Object> values,
		String field
	) {
		if (!values.containsKey(field) || values.get(field) == null) {
			return;
		}
		Object value = values.get(field);
		if (!(value instanceof Number number)
			|| number.longValue() < 1
			|| number.doubleValue() != number.longValue()) {
			throw policy(
				"statePatch.%s must be a positive integer".formatted(field)
			);
		}
	}

	private String text(Map<String, Object> values, String field) {
		return valueText(values.get(field));
	}

	private String valueText(Object value) {
		return value instanceof String text ? text : null;
	}

	private BigDecimal decimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			try {
				return new BigDecimal(number.toString());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private BusinessException invalid(String reason) {
		return rejected(ErrorCode.AI_RESPONSE_INVALID, reason);
	}

	private BusinessException policy(String reason) {
		return rejected(ErrorCode.AI_POLICY_REJECTED, reason);
	}

	private BusinessException rejected(ErrorCode errorCode, String reason) {
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("validator", TurnResponseValidator.class.getSimpleName())
			.addKeyValue("errorCode", errorCode.code())
			.addKeyValue("reason", reason)
			.log("AI response validation rejected");
		return new BusinessException(errorCode);
	}
}
