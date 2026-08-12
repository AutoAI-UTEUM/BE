package io.edupilot.report;

import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

final class ReportVersionConflictException extends RuntimeException {

	static final String CONSTRAINT_NAME =
		"uk_student_reports_classroom_student_scope_version";

	private final String scopeKey;
	private final int version;

	ReportVersionConflictException(
		String scopeKey,
		int version,
		DataIntegrityViolationException cause
	) {
		super(cause);
		this.scopeKey = scopeKey;
		this.version = version;
	}

	static boolean matches(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException constraintViolation
				&& CONSTRAINT_NAME.equalsIgnoreCase(
					constraintViolation.getConstraintName()
				)) {
				return true;
			}
			String message = cause.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(
				CONSTRAINT_NAME.toLowerCase(Locale.ROOT)
			)) {
				return true;
			}
		}
		return false;
	}

	String scopeKey() { return scopeKey; }
	int version() { return version; }
}
