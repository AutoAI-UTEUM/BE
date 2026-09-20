package io.edupilot.ai.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class AiUsageTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void acceptsSnakeAndCamelCaseCostTicks() throws Exception {
		AiUsage snake = objectMapper.readValue(
			"{\"cost_usd_ticks\":123}",
			AiUsage.class
		);
		AiUsage camel = objectMapper.readValue(
			"{\"costUsdTicks\":456}",
			AiUsage.class
		);

		assertThat(snake.costUsdTicks()).isEqualTo(123L);
		assertThat(camel.costUsdTicks()).isEqualTo(456L);
	}

	@Test
	void keepsMissingCostAsNullInsteadOfZero() throws Exception {
		AiUsage usage = objectMapper.readValue("{}", AiUsage.class);

		assertThat(usage.costUsdTicks()).isNull();
	}
}
