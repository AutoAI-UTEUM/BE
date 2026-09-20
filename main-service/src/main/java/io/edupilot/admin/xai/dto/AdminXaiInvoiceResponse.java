package io.edupilot.admin.xai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

public record AdminXaiInvoiceResponse(
	LocalDate periodFrom,
	LocalDate periodTo,
	@JsonFormat(shape = JsonFormat.Shape.STRING)
	BigDecimal amountUsd,
	String status
) {
}
