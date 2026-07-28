package io.edupilot.global.response;

public record ErrorDetail(
	String field,
	String reason
) {
}
