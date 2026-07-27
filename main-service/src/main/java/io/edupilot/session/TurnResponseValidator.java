package io.edupilot.session;

import java.math.BigDecimal;
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

	public void validate(TurnResponse response, String expectedTurnId) {
		if (response == null
			|| !expectedTurnId.equals(response.turnId())
			|| response.messages() == null
			|| response.statePatch() == null
			|| response.uiActions() == null
			|| response.memoryCandidates() == null) {
			throw invalid();
		}
		validateMessages(response);
		validateStatePatch(response.statePatch());
		warnIgnoredUiActions(response);
		validateMemoryCandidates(response);
	}

	private void validateMessages(TurnResponse response) {
		for (Map<String, Object> message : response.messages()) {
			if (message == null
				|| !MESSAGE_TYPES.contains(text(message, "messageType"))
				|| !StringUtils.hasText(text(message, "content"))) {
				throw invalid();
			}
		}
	}

	private void validateStatePatch(Map<String, Object> patch) {
		if (!PATCH_FIELDS.containsAll(patch.keySet())) {
			throw policy();
		}
		if (patch.containsKey("pageStatus")
			&& !PAGE_STATUSES.contains(text(patch, "pageStatus"))) {
			throw policy();
		}
		validateNullablePositiveLong(patch, "activeQuizId");
		validateNullablePositiveLong(patch, "pendingDiagnosis");
		if (!patch.containsKey("qaThread")) {
			return;
		}
		Object raw = patch.get("qaThread");
		if (!(raw instanceof Map<?, ?> qaThread)
			|| !Set.of("mode", "threadRef").containsAll(
				qaThread.keySet().stream().map(String::valueOf).toList()
			)) {
			throw policy();
		}
		String mode = valueText(qaThread.get("mode"));
		String threadRef = valueText(qaThread.get("threadRef"));
		if ("START_NEW".equals(mode)) {
			if (threadRef != null && !threadRef.isBlank()) {
				throw policy();
			}
			return;
		}
		if (!"FOLLOW_UP".equals(mode)
			|| threadRef == null
			|| !threadRef.matches("qa-[1-9][0-9]*")) {
			throw policy();
		}
	}

	private void warnIgnoredUiActions(TurnResponse response) {
		if (response.uiActions().isEmpty()) {
			return;
		}
		log.atWarn()
			.addKeyValue(
				"traceId",
				MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)
			)
			.addKeyValue("turnId", response.turnId())
			.addKeyValue("uiActionCount", response.uiActions().size())
			.log("Ignored non-empty AI uiActions");
	}

	private void validateMemoryCandidates(TurnResponse response) {
		for (Map<String, Object> candidate : response.memoryCandidates()) {
			if (candidate == null
				|| !StringUtils.hasText(text(candidate, "type"))
				|| !StringUtils.hasText(text(candidate, "content"))) {
				throw invalid();
			}
			BigDecimal confidence = decimal(candidate.get("confidence"));
			if (confidence == null
				|| confidence.compareTo(BigDecimal.ZERO) < 0
				|| confidence.compareTo(BigDecimal.ONE) > 0) {
				throw invalid();
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
			throw policy();
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

	private BusinessException invalid() {
		return new BusinessException(ErrorCode.AI_RESPONSE_INVALID);
	}

	private BusinessException policy() {
		return new BusinessException(ErrorCode.AI_POLICY_REJECTED);
	}
}
