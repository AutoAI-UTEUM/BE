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
		ValidationContext context = new ValidationContext(
			expectedTurnId,
			eventType
		);
		if (response == null) {
			throw invalid(context, "response", "non-null object", "null");
		}
		if (!expectedTurnId.equals(response.turnId())) {
			throw invalid(context, "turnId", "request turnId", "mismatch");
		}
		if (response.messages() == null) {
			throw invalid(context, "messages", "array", "null");
		}
		if (response.statePatch() == null) {
			throw invalid(context, "statePatch", "object", "null");
		}
		if (response.uiActions() == null) {
			throw invalid(context, "uiActions", "array", "null");
		}
		if (response.memoryCandidates() == null) {
			throw invalid(context, "memoryCandidates", "array", "null");
		}
		validateMessages(response, context);
		validateStatePatch(
			response.statePatch(),
			expectedQaThreadRef,
			context
		);
		validateQuiz(
			response,
			eventType,
			expectedQuizType,
			availableQuizPages,
			context
		);
		validateNoteDraft(response, context);
		warnIgnoredUiActions(
			response.turnId(),
			response.uiActions().stream()
				.filter(action ->
					!isMoveNextPageProposal(action)
						&& !isNoteProposal(action))
				.toList()
		);
		warnIgnoredActiveQuizId(response);
		validateMemoryCandidates(response, context);
	}

	private void validateMessages(
		TurnResponse response,
		ValidationContext context
	) {
		for (int index = 0; index < response.messages().size(); index++) {
			Map<String, Object> message = response.messages().get(index);
			if (message == null) {
				throw invalid(
					context,
					"messages[%d]".formatted(index),
					"object",
					"null"
				);
			}
			if (!MESSAGE_TYPES.contains(text(message, "messageType"))) {
				throw invalid(
					context,
					"messages[%d].messageType".formatted(index),
					MESSAGE_TYPES,
					enumValue(message.get("messageType"))
				);
			}
			if (!StringUtils.hasText(text(message, "content"))) {
				throw invalid(
					context,
					"messages[%d].content".formatted(index),
					"non-blank text",
					textState(message.get("content"))
				);
			}
		}
	}

	private void validateStatePatch(
		Map<String, Object> patch,
		String expectedQaThreadRef,
		ValidationContext context
	) {
		if (!PATCH_FIELDS.containsAll(patch.keySet())) {
			throw policy(
				context,
				"statePatch.fields",
				PATCH_FIELDS,
				patch.keySet()
			);
		}
		if (patch.containsKey("pageStatus")
			&& !PAGE_STATUSES.contains(text(patch, "pageStatus"))) {
			throw policy(
				context,
				"statePatch.pageStatus",
				PAGE_STATUSES,
				enumValue(patch.get("pageStatus"))
			);
		}
		validateNullablePositiveLong(
			patch,
			"pendingDiagnosis",
			context
		);
		if (!patch.containsKey("qaThread")) {
			return;
		}
		Object raw = patch.get("qaThread");
		if (!(raw instanceof Map<?, ?> qaThread)
			|| qaThread.keySet().stream()
				.anyMatch(key -> !(key instanceof String))) {
			throw policy(
				context,
				"statePatch.qaThread",
				"object with string keys",
				valueType(raw)
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
					context,
					"statePatch.qaThread.fields",
					Set.of("mode"),
					keys
				);
			}
			return;
		}
		if (!"FOLLOW_UP".equals(mode)) {
			throw policy(
				context,
				"statePatch.qaThread.mode",
				Set.of("START_NEW", "FOLLOW_UP"),
				enumValue(qaThread.get("mode"))
			);
		}
		if (!keys.equals(Set.of("mode", "threadRef"))) {
			throw policy(
				context,
				"statePatch.qaThread.fields",
				Set.of("mode", "threadRef"),
				keys
			);
		}
		if (threadRef == null
			|| !threadRef.matches("qa-[1-9][0-9]*")) {
			throw policy(
				context,
				"statePatch.qaThread.threadRef",
				"qa-{positive integer}",
				threadRef == null ? "null or non-text" : "malformed text"
			);
		}
		if (!threadRef.equals(expectedQaThreadRef)) {
			throw policy(
				context,
				"statePatch.qaThread.threadRef",
				"snapshot threadRef",
				"different reference"
			);
		}
	}

	static boolean isMoveNextPageProposal(Map<String, Object> action) {
		return action != null
			&& "BINARY_DECISION".equals(action.get("type"))
			&& "MOVE_NEXT_PAGE".equals(action.get("yesEvent"))
			&& "WAIT".equals(action.get("noEvent"));
	}

	static boolean isNoteProposal(Map<String, Object> action) {
		return action != null
			&& "BINARY_DECISION".equals(action.get("type"))
			&& "NOTE_REQUESTED".equals(action.get("yesEvent"))
			&& "WAIT".equals(action.get("noEvent"))
			&& action.get("content") instanceof String content
			&& StringUtils.hasText(content);
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
		Set<Integer> availableQuizPages,
		ValidationContext context
	) {
		boolean quizTurn = eventType == TurnEventType.QUIZ_TYPE_SELECTED;
		if (quizTurn != (response.quiz() != null)) {
			throw invalid(
				context,
				"quiz.presence",
				quizTurn,
				response.quiz() != null
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

	private void validateNoteDraft(
		TurnResponse response,
		ValidationContext context
	) {
		if (response.noteDraft() == null) {
			return;
		}
		if (!StringUtils.hasText(response.noteDraft().title())
			|| response.noteDraft().title().length() > 60) {
			throw invalid(
				context,
				"noteDraft.title",
				"non-blank text length<=60",
				textState(response.noteDraft().title())
			);
		}
		if (!StringUtils.hasText(response.noteDraft().content())) {
			throw invalid(
				context,
				"noteDraft.content",
				"non-blank text",
				textState(response.noteDraft().content())
			);
		}
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

	private void validateMemoryCandidates(
		TurnResponse response,
		ValidationContext context
	) {
		for (int index = 0;
			index < response.memoryCandidates().size();
			index++) {
			Map<String, Object> candidate =
				response.memoryCandidates().get(index);
			if (candidate == null) {
				throw invalid(
					context,
					"memoryCandidates[%d]".formatted(index),
					"object",
					"null"
				);
			}
			if (!candidate.keySet().equals(MEMORY_CANDIDATE_FIELDS)) {
				throw invalid(
					context,
					"memoryCandidates[%d].fields".formatted(index),
					MEMORY_CANDIDATE_FIELDS,
					candidate.keySet()
				);
			}
			if (!MEMORY_CANDIDATE_TYPES.contains(
				text(candidate, "type")
			)) {
				throw invalid(
					context,
					"memoryCandidates[%d].type".formatted(index),
					MEMORY_CANDIDATE_TYPES,
					enumValue(candidate.get("type"))
				);
			}
			if (!StringUtils.hasText(text(candidate, "content"))) {
				throw invalid(
					context,
					"memoryCandidates[%d].content".formatted(index),
					"non-blank text",
					textState(candidate.get("content"))
				);
			}
			if (!(candidate.get("promotionRequested") instanceof Boolean)) {
				throw invalid(
					context,
					"memoryCandidates[%d].promotionRequested".formatted(index),
					"boolean",
					valueType(candidate.get("promotionRequested"))
				);
			}
			BigDecimal confidence = decimal(candidate.get("confidence"));
			if (confidence == null) {
				throw invalid(
					context,
					"memoryCandidates[%d].confidence".formatted(index),
					"number between 0 and 1",
					valueType(candidate.get("confidence"))
				);
			}
			if (confidence.compareTo(BigDecimal.ZERO) < 0
				|| confidence.compareTo(BigDecimal.ONE) > 0) {
				throw invalid(
					context,
					"memoryCandidates[%d].confidence".formatted(index),
					"0..1",
					confidence
				);
			}
			validateMemoryEvidence(
				candidate.get("evidence"),
				index,
				context
			);
		}
	}

	private void validateMemoryEvidence(
		Object value,
		int candidateIndex,
		ValidationContext context
	) {
		if (!(value instanceof List<?> evidence)) {
			throw invalid(
				context,
				"memoryCandidates[%d].evidence".formatted(candidateIndex),
				"non-empty array",
				valueType(value)
			);
		}
		if (evidence.isEmpty()) {
			throw invalid(
				context,
				"memoryCandidates[%d].evidence".formatted(candidateIndex),
				"size>=1",
				"size=0"
			);
		}
		Set<String> unique = new HashSet<>();
		for (int index = 0; index < evidence.size(); index++) {
			Object item = evidence.get(index);
			if (!(item instanceof String text)) {
				throw invalid(
					context,
					"memoryCandidates[%d].evidence[%d]"
						.formatted(candidateIndex, index),
					"text",
					valueType(item)
				);
			}
			if (!StringUtils.hasText(text)) {
				throw invalid(
					context,
					"memoryCandidates[%d].evidence[%d]"
						.formatted(candidateIndex, index),
					"non-blank text",
					textState(text)
				);
			}
			if (!unique.add(text.trim())) {
				throw invalid(
					context,
					"memoryCandidates[%d].evidence.uniqueness"
						.formatted(candidateIndex),
					"unique items",
					"duplicate at index=%d".formatted(index)
				);
			}
		}
	}

	private void validateNullablePositiveLong(
		Map<String, Object> values,
		String field,
		ValidationContext context
	) {
		if (!values.containsKey(field) || values.get(field) == null) {
			return;
		}
		Object value = values.get(field);
		if (!(value instanceof Number number)
			|| number.longValue() < 1
			|| number.doubleValue() != number.longValue()) {
			throw policy(
				context,
				"statePatch.%s".formatted(field),
				"positive integer",
				numericState(value)
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

	private String enumValue(Object value) {
		return value instanceof String ? textState(value) : valueType(value);
	}

	private String textState(Object value) {
		if (!(value instanceof String text)) {
			return valueType(value);
		}
		return StringUtils.hasText(text)
			? "text length=%d".formatted(text.length())
			: "blank text length=%d".formatted(text.length());
	}

	private String numericState(Object value) {
		return value instanceof Number ? value.toString() : valueType(value);
	}

	private String valueType(Object value) {
		return value == null ? "null" : value.getClass().getSimpleName();
	}

	private BusinessException invalid(
		ValidationContext context,
		String validationItem,
		Object expected,
		Object actual
	) {
		return rejected(
			context,
			ErrorCode.AI_RESPONSE_INVALID,
			validationItem,
			expected,
			actual
		);
	}

	private BusinessException policy(
		ValidationContext context,
		String validationItem,
		Object expected,
		Object actual
	) {
		return rejected(
			context,
			ErrorCode.AI_POLICY_REJECTED,
			validationItem,
			expected,
			actual
		);
	}

	private BusinessException rejected(
		ValidationContext context,
		ErrorCode errorCode,
		String validationItem,
		Object expected,
		Object actual
	) {
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("turnId", context.turnId())
			.addKeyValue(
				"eventType",
				context.eventType() == null
					? "UNKNOWN"
					: context.eventType().name()
			)
			.addKeyValue("validator", TurnResponseValidator.class.getSimpleName())
			.addKeyValue("errorCode", errorCode.code())
			.addKeyValue("validationItem", validationItem)
			.addKeyValue("expected", expected)
			.addKeyValue("actual", actual)
			.log("AI response validation failed");
		return new BusinessException(errorCode);
	}

	private record ValidationContext(
		String turnId,
		TurnEventType eventType
	) {
	}
}
