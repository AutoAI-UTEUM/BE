package io.edupilot.material;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edupilot.ai.page-quiz-plan")
public record PageQuizPlanProperties(boolean enabled, boolean backfillEnabled) {
}
