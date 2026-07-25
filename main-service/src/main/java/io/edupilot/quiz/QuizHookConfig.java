package io.edupilot.quiz;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class QuizHookConfig {

	@Bean
	@ConditionalOnMissingBean(QuizPostGradingHook.class)
	QuizPostGradingHook quizPostGradingHook() {
		return submission -> {
		};
	}
}
