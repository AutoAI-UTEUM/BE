package io.edupilot.ai;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class AiClientException extends BusinessException {

	private final AiFailureCategory category;
	private final boolean retryable;

	public AiClientException(ErrorCode errorCode) {
		this(errorCode, false, null);
	}

	public AiClientException(ErrorCode errorCode, Throwable cause) {
		this(errorCode, false, cause);
	}

	public AiClientException(
		ErrorCode errorCode,
		boolean retryable,
		Throwable cause
	) {
		this(errorCode, categoryFor(errorCode), retryable, cause);
	}

	public AiClientException(
		ErrorCode errorCode,
		AiFailureCategory category,
		boolean retryable,
		Throwable cause
	) {
		super(errorCode);
		this.category = category;
		this.retryable = retryable;
		if (cause != null) {
			initCause(cause);
		}
	}

	public AiFailureCategory category() {
		return category;
	}

	public boolean retryable() {
		return retryable;
	}

	private static AiFailureCategory categoryFor(ErrorCode errorCode) {
		return switch (errorCode) {
			case AI_SERVICE_TIMEOUT -> AiFailureCategory.TIMEOUT;
			case AI_RESPONSE_INVALID -> AiFailureCategory.SCHEMA;
			case AI_POLICY_REJECTED -> AiFailureCategory.POLICY;
			default -> AiFailureCategory.INTERNAL;
		};
	}
}
