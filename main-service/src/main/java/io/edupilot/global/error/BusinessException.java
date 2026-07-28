package io.edupilot.global.error;

public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;
	private final String clientMessage;

	public BusinessException(ErrorCode errorCode) {
		this(errorCode, errorCode.message());
	}

	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
		this.clientMessage = message;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}

	public String clientMessage() {
		return clientMessage;
	}
}
