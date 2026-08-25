package io.edupilot.session;

record SessionStreamError(
	String code,
	String category,
	String message,
	boolean retryable,
	String traceId
) {
}
