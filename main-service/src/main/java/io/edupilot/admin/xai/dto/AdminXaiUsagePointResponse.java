package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AdminXaiUsagePointResponse(
	LocalDate date,
	String group,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal costUsd,
	Long tokenCount,
	Long callCount
) {
}
