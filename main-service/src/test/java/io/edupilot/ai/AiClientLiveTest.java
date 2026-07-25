package io.edupilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.MDC;

import io.edupilot.ai.dto.TurnRequest;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.global.security.TraceIdFilter;

@EnabledIfSystemProperty(named = "it.ai", matches = "true")
class AiClientLiveTest {

	@Test
	void liveHealthTurnAndInvalidTokenContract() {
		String baseUrl = System.getenv().getOrDefault(
			"EDUPILOT_AI_BASE_URL",
			"http://localhost:8000"
		);
		String token = requiredEnvironment("EDUPILOT_INTERNAL_TOKEN");
		AiClientProperties properties = properties(baseUrl, token);
		HttpAiClient client = new HttpAiClient(properties);
		MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, "live-contract-test");

		try {
			assertThat(client.health().status()).isNotBlank();

			var response = client.executeTurn(new TurnRequest(
				"1.0",
				"live-turn-1",
				Map.of(),
				Map.of("eventType", "USER_QUESTION", "payload", Map.of()),
				Map.of()
			));
			assertThat(response.schemaVersion()).isEqualTo("1.0");
			assertThat(response.turnId()).isEqualTo("live-turn-1");

			HttpAiClient invalidTokenClient = new HttpAiClient(
				properties(baseUrl, token + "-invalid")
			);
			assertThatThrownBy(invalidTokenClient::health)
				.isInstanceOfSatisfying(AiClientException.class, exception ->
					assertThat(exception.errorCode())
						.isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR));
		} finally {
			MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
		}
	}

	private AiClientProperties properties(String baseUrl, String token) {
		return new AiClientProperties(
			URI.create(baseUrl),
			token,
			Duration.ofSeconds(3),
			Duration.ofSeconds(30),
			Duration.ofSeconds(90),
			Duration.ofSeconds(120),
			"/health"
		);
	}

	private String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " 환경 변수가 필요합니다.");
		}
		return value;
	}
}
