package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

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
			"turn-1"
		);
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
		return new io.edupilot.ai.dto.TurnResponse(
			"1.0",
			"turn-1",
			"ANSWER",
			List.of(),
			messages,
			patch,
			List.of(),
			List.of(),
			null,
			null
		);
	}
}
