package io.edupilot.ai;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

public class AiClientException extends BusinessException {

	public AiClientException(ErrorCode errorCode) {
		super(errorCode);
	}

	public AiClientException(ErrorCode errorCode, Throwable cause) {
		super(errorCode);
		initCause(cause);
	}
}
