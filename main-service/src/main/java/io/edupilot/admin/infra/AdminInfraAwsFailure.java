package io.edupilot.admin.infra;

import java.util.Locale;
import java.util.regex.Pattern;

import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;

final class AdminInfraAwsFailure {

	private static final int MAX_MESSAGE_LENGTH = 500;
	private static final Pattern ACCESS_KEY = Pattern.compile(
		"(?i)\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"
	);

	private AdminInfraAwsFailure() {
	}

	static String reason(RuntimeException exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof ApiCallTimeoutException
				|| current instanceof ApiCallAttemptTimeoutException) {
				return "TIMEOUT";
			}
			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toLowerCase(Locale.ROOT);
				if (normalized.contains("timed out")
					|| normalized.contains("timeout")) {
					return "TIMEOUT";
				}
			}
			current = current.getCause();
		}
		return "AWS_ERROR";
	}

	static String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) {
			return exception.getClass().getSimpleName();
		}
		String sanitized = ACCESS_KEY.matcher(message)
			.replaceAll("[REDACTED_ACCESS_KEY]")
			.replace('\r', ' ')
			.replace('\n', ' ');
		return sanitized.length() <= MAX_MESSAGE_LENGTH
			? sanitized
			: sanitized.substring(0, MAX_MESSAGE_LENGTH);
	}
}
