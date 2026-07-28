package io.edupilot.quiz;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.quiz")
public record QuizProperties(
	@NotNull
	@DecimalMin("0.0")
	@DecimalMax("1.0")
	BigDecimal passRatio
) {
}
