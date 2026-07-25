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
	EMAIL_ALREADY_EXISTS(
		"EMAIL_ALREADY_EXISTS",
		HttpStatus.CONFLICT,
		"이미 사용 중인 이메일입니다."
	),
	INVALID_CREDENTIALS(
		"INVALID_CREDENTIALS",
		HttpStatus.UNAUTHORIZED,
		"이메일 또는 비밀번호가 올바르지 않습니다."
	),
	USER_INACTIVE("USER_INACTIVE", HttpStatus.FORBIDDEN, "비활성화된 사용자입니다."),
	USER_NOT_FOUND("USER_NOT_FOUND", HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
	MATERIAL_NOT_FOUND(
		"MATERIAL_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"학습 자료를 찾을 수 없습니다."
	),
	INVALID_PDF_FILE(
		"INVALID_PDF_FILE",
		HttpStatus.BAD_REQUEST,
		"유효한 PDF 파일을 업로드해 주세요."
	),
	FILE_TOO_LARGE(
		"FILE_TOO_LARGE",
		HttpStatus.CONTENT_TOO_LARGE,
		"파일 크기 제한을 초과했습니다."
	),
	MATERIAL_PROCESSING(
		"MATERIAL_PROCESSING",
		HttpStatus.CONFLICT,
		"학습 자료를 처리하고 있습니다."
	),
	MATERIAL_PROCESSING_FAILED(
		"MATERIAL_PROCESSING_FAILED",
		HttpStatus.CONFLICT,
		"학습 자료 처리에 실패했습니다."
	),
	MATERIAL_HAS_ACTIVE_SESSION(
		"MATERIAL_HAS_ACTIVE_SESSION",
		HttpStatus.CONFLICT,
		"진행 중인 학습 세션이 있어 자료를 삭제할 수 없습니다."
	),
	PAGE_OUT_OF_RANGE(
		"PAGE_OUT_OF_RANGE",
		HttpStatus.BAD_REQUEST,
		"페이지 번호가 자료 범위를 벗어났습니다."
	),
	SESSION_NOT_FOUND(
		"SESSION_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"학습 세션을 찾을 수 없습니다."
	),
	SESSION_NOT_ACTIVE(
		"SESSION_NOT_ACTIVE",
		HttpStatus.CONFLICT,
		"활성 상태의 학습 세션이 아닙니다."
	),
	SESSION_STATE_CONFLICT(
		"SESSION_STATE_CONFLICT",
		HttpStatus.CONFLICT,
		"현재 세션 상태에서 요청을 처리할 수 없습니다."
	),
	UNSUPPORTED_EVENT_TYPE(
		"UNSUPPORTED_EVENT_TYPE",
		HttpStatus.BAD_REQUEST,
		"지원하지 않는 학습 이벤트입니다."
	),
	TURN_ALREADY_PROCESSED(
		"TURN_ALREADY_PROCESSED",
		HttpStatus.CONFLICT,
		"이미 처리된 학습 턴입니다."
	),
	TURN_IN_PROGRESS(
		"TURN_IN_PROGRESS",
		HttpStatus.CONFLICT,
		"다른 학습 턴을 처리하고 있습니다."
	),
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
