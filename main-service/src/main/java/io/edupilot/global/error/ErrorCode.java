package io.edupilot.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	VALIDATION_FAILED("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),
	MALFORMED_REQUEST("MALFORMED_REQUEST", HttpStatus.BAD_REQUEST, "요청 형식을 확인해 주세요."),
	UNSUPPORTED_MEDIA_TYPE(
		"UNSUPPORTED_MEDIA_TYPE",
		HttpStatus.UNSUPPORTED_MEDIA_TYPE,
		"지원하지 않는 콘텐츠 타입입니다."
	),
	AUTHENTICATION_REQUIRED(
		"AUTHENTICATION_REQUIRED",
		HttpStatus.UNAUTHORIZED,
		"인증이 필요합니다."
	),
	TOKEN_INVALID("TOKEN_INVALID", HttpStatus.UNAUTHORIZED, "유효하지 않은 인증 토큰입니다."),
	TOKEN_EXPIRED("TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED, "인증 토큰이 만료되었습니다."),
	ACCESS_DENIED("ACCESS_DENIED", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
	RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
	RATE_LIMIT_EXCEEDED(
		"RATE_LIMIT_EXCEEDED",
		HttpStatus.TOO_MANY_REQUESTS,
		"요청 한도를 초과했습니다."
	),
	AI_SERVICE_UNAVAILABLE(
		"AI_SERVICE_UNAVAILABLE",
		HttpStatus.SERVICE_UNAVAILABLE,
		"AI 서비스를 일시적으로 사용할 수 없습니다."
	),
	AI_SERVICE_TIMEOUT(
		"AI_SERVICE_TIMEOUT",
		HttpStatus.GATEWAY_TIMEOUT,
		"AI 서비스 응답 시간이 초과되었습니다."
	),
	AI_RESPONSE_INVALID(
		"AI_RESPONSE_INVALID",
		HttpStatus.BAD_GATEWAY,
		"AI 서비스 응답을 처리할 수 없습니다."
	),
	AI_POLICY_REJECTED(
		"AI_POLICY_REJECTED",
		HttpStatus.BAD_GATEWAY,
		"AI 서비스가 요청을 처리하지 못했습니다."
	),
	AI_STREAM_INTERRUPTED(
		"AI_STREAM_INTERRUPTED",
		HttpStatus.BAD_GATEWAY,
		"AI 응답 스트림이 중단되었습니다."
	),
	INTERNAL_SERVER_ERROR(
		"INTERNAL_SERVER_ERROR",
		HttpStatus.INTERNAL_SERVER_ERROR,
		"서버 오류가 발생했습니다."
	);

	private final String code;
	private final HttpStatus status;
	private final String message;

	ErrorCode(String code, HttpStatus status, String message) {
		this.code = code;
		this.status = status;
		this.message = message;
	}

	public String code() {
		return code;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}
}
