package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

class TurnResponseValidatorTest {

	private final TurnResponseValidator validator =
		new TurnResponseValidator();

	@Test
	void acceptsSystemMessageAndFollowUpThreadReference() {
		validator.validate(
			response(
				List.of(Map.of(
					"messageType",
					"SYSTEM",
					"content",
					"안내"
				)),
				Map.of(
					"qaThread",
					Map.of(
						"mode",
						"FOLLOW_UP",
						"threadRef",
						"qa-30"
					)
				)
			),
			"turn-1",
			"qa-30"
		);
	}

	@Test
	void enforcesExactQaThreadPatchAndSnapshotReference() {
		validator.validate(
			response(
				List.of(),
				Map.of("qaThread", Map.of("mode", "START_NEW"))
			),
			"turn-1"
		);
		assertPolicy(Map.of(
			"qaThread",
			Map.of("mode", "START_NEW", "threadRef", "qa-30")
		));
		assertPolicy(Map.of(
			"qaThread",
			Map.of("mode", "FOLLOW_UP")
		));
		assertThatThrownBy(() -> validator.validate(
			response(
				List.of(),
				Map.of(
					"qaThread",
					Map.of("mode", "FOLLOW_UP", "threadRef", "qa-31")
				)
			),
			"turn-1",
			"qa-30"
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode())
				.isEqualTo(ErrorCode.AI_POLICY_REJECTED)
		);
	}

	@Test
	void ignoresAiUiActionsAndWarnsWithoutLoggingTheirValue() {
		String sentinel = "SENTINEL_RAW_UI_ACTION";
		Logger logger =
			(Logger) LoggerFactory.getLogger(TurnResponseValidator.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "trace-74");
		try {
			validator.validate(
				response(
					List.of(),
					Map.of(),
					List.of(Map.of("content", sentinel))
				),
				"turn-1"
			);
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
			logger.detachAppender(appender);
			appender.stop();
		}

		assertThat(appender.list)
			.filteredOn(event -> event.getFormattedMessage().equals(
				"Ignored non-empty AI uiActions"
			))
			.singleElement()
			.satisfies(event -> {
				Map<String, String> fields = event.getKeyValuePairs().stream()
					.collect(Collectors.toMap(
						pair -> pair.key,
						pair -> String.valueOf(pair.value)
					));
				assertThat(fields)
					.containsEntry("traceId", "trace-74")
					.containsEntry("turnId", "turn-1")
					.containsEntry("uiActionCount", "1");
				assertThat(event.getFormattedMessage())
					.doesNotContain(sentinel);
				assertThat(event.getKeyValuePairs().toString())
					.doesNotContain(sentinel);
			});
	}

	@Test
	void rejectsUnknownPatchAndNotExplainedRegression() {
		assertPolicy(Map.of("status", "COMPLETED"));
		assertPolicy(Map.of("pageStatus", "NOT_EXPLAINED"));
	}

	private void assertPolicy(Map<String, Object> patch) {
		assertThatThrownBy(() ->
			validator.validate(response(List.of(), patch), "turn-1"))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_POLICY_REJECTED)
			);
	}

	private io.edupilot.ai.dto.TurnResponse response(
		List<Map<String, Object>> messages,
		Map<String, Object> patch
	) {
		return response(messages, patch, List.of());
	}

	private io.edupilot.ai.dto.TurnResponse response(
		List<Map<String, Object>> messages,
		Map<String, Object> patch,
		List<Map<String, Object>> uiActions
	) {
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"ANSWER",
			List.of(),
			messages,
			patch,
			uiActions,
			List.of(),
			null,
			null
		);
	}
}
