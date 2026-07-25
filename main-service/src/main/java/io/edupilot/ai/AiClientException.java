package io.edupilot.ai;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class AiClientException extends BusinessException {

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
		super(errorCode);
		this.retryable = retryable;
		if (cause != null) {
			initCause(cause);
		}
	}

	public boolean retryable() {
		return retryable;
	}
}
