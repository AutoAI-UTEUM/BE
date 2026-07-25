package io.edupilot.auth;

import io.edupilot.global.error.ErrorCode;

public class JwtTokenValidationException extends RuntimeException {

	private final ErrorCode errorCode;

	public JwtTokenValidationException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.message(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}
}
