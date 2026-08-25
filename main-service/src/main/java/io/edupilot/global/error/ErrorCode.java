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
	CLASSROOM_NOT_FOUND(
		"CLASSROOM_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"강의실을 찾을 수 없습니다."
	),
	INVALID_INVITE_CODE(
		"INVALID_INVITE_CODE",
		HttpStatus.NOT_FOUND,
		"유효하지 않은 초대 코드입니다."
	),
	ALREADY_CLASSROOM_MEMBER(
		"ALREADY_CLASSROOM_MEMBER",
		HttpStatus.CONFLICT,
		"이미 강의실에 참여하고 있습니다."
	),
	JOIN_REQUEST_ALREADY_PENDING(
		"JOIN_REQUEST_ALREADY_PENDING",
		HttpStatus.CONFLICT,
		"이미 대기 중인 참여 요청이 있습니다."
	),
	JOIN_REQUEST_ALREADY_PROCESSED(
		"JOIN_REQUEST_ALREADY_PROCESSED",
		HttpStatus.CONFLICT,
		"이미 처리된 참여 요청입니다."
	),
	CLASSROOM_COMPLETED(
		"CLASSROOM_COMPLETED",
		HttpStatus.CONFLICT,
		"완료된 강의실에서는 이 작업을 수행할 수 없습니다."
	),
	WEEK_NOT_FOUND(
		"WEEK_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"강의실 주차를 찾을 수 없습니다."
	),
	WEEK_ALREADY_EXISTS(
		"WEEK_ALREADY_EXISTS",
		HttpStatus.CONFLICT,
		"같은 번호의 강의실 주차가 이미 존재합니다."
	),
	MATERIAL_ALREADY_LINKED(
		"MATERIAL_ALREADY_LINKED",
		HttpStatus.CONFLICT,
		"자료가 이미 해당 주차에 연결되어 있습니다."
	),
	MATERIAL_LINKED_TO_CLASSROOM(
		"MATERIAL_LINKED_TO_CLASSROOM",
		HttpStatus.CONFLICT,
		"강의실에 연결된 자료는 삭제할 수 없습니다."
	),
	CLASSROOM_WEEK_RANGE_CONFLICT(
		"CLASSROOM_WEEK_RANGE_CONFLICT",
		HttpStatus.CONFLICT,
		"기존 주차가 변경할 강의실 기간을 벗어납니다."
	),
	EMAIL_ALREADY_EXISTS(
		"EMAIL_ALREADY_EXISTS",
		HttpStatus.CONFLICT,
		"이미 사용 중인 이메일입니다."
	),
	SIGNUP_REQUIRED(
		"SIGNUP_REQUIRED",
		HttpStatus.CONFLICT,
		"추가 정보 입력이 필요합니다."
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
	NOTE_NOT_FOUND(
		"NOTE_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"노트를 찾을 수 없습니다."
	),
	SCHEDULE_NOT_FOUND(
		"SCHEDULE_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"일정을 찾을 수 없습니다."
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
	TURN_CANCELLED(
		"TURN_CANCELLED",
		HttpStatus.CONFLICT,
		"학습 턴이 취소되었습니다."
	),
	QUIZ_NOT_FOUND(
		"QUIZ_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"퀴즈를 찾을 수 없습니다."
	),
	UNSUPPORTED_QUIZ_TYPE(
		"UNSUPPORTED_QUIZ_TYPE",
		HttpStatus.BAD_REQUEST,
		"지원하지 않는 퀴즈 유형입니다."
	),
	INVALID_QUIZ_ANSWER(
		"INVALID_QUIZ_ANSWER",
		HttpStatus.BAD_REQUEST,
		"퀴즈 답안을 확인해 주세요."
	),
	QUIZ_ALREADY_SUBMITTED(
		"QUIZ_ALREADY_SUBMITTED",
		HttpStatus.CONFLICT,
		"이미 제출한 퀴즈입니다."
	),
	QUIZ_NOT_SUBMITTABLE(
		"QUIZ_NOT_SUBMITTABLE",
		HttpStatus.CONFLICT,
		"현재 제출할 수 없는 퀴즈입니다."
	),
	EXAM_NOT_FOUND(
		"EXAM_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"시험을 찾을 수 없습니다."
	),
	EXAM_NOT_PUBLISHED(
		"EXAM_NOT_PUBLISHED",
		HttpStatus.CONFLICT,
		"공개된 시험이 아닙니다."
	),
	EXAM_NOT_EDITABLE(
		"EXAM_NOT_EDITABLE",
		HttpStatus.CONFLICT,
		"수정 가능한 시험이 아닙니다."
	),
	EXAM_ALREADY_SUBMITTED(
		"EXAM_ALREADY_SUBMITTED",
		HttpStatus.CONFLICT,
		"이미 제출한 시험입니다."
	),
	INVALID_EXAM_ANSWER(
		"INVALID_EXAM_ANSWER",
		HttpStatus.BAD_REQUEST,
		"시험 답안을 확인해 주세요."
	),
	GRADING_RESULT_INVALID(
		"GRADING_RESULT_INVALID",
		HttpStatus.BAD_GATEWAY,
		"채점 결과를 처리할 수 없습니다."
	),
	DIAGNOSIS_NOT_FOUND(
		"DIAGNOSIS_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"진단을 찾을 수 없습니다."
	),
	DIAGNOSIS_NOT_PENDING(
		"DIAGNOSIS_NOT_PENDING",
		HttpStatus.CONFLICT,
		"답변 대기 상태의 진단이 아닙니다."
	),
	REPORT_NOT_FOUND(
		"REPORT_NOT_FOUND",
		HttpStatus.NOT_FOUND,
		"리포트를 찾을 수 없습니다."
	),
	REPORT_CRITERION_LIMIT_EXCEEDED(
		"REPORT_CRITERION_LIMIT_EXCEEDED",
		HttpStatus.BAD_REQUEST,
		"활성 리포트 평가 기준은 20개를 초과할 수 없습니다."
	),
	REPORT_CRITERIA_GENERATION_NOT_READY(
		"REPORT_CRITERIA_GENERATION_NOT_READY",
		HttpStatus.BAD_REQUEST,
		"개요가 생성된 자료가 없습니다"
	),
	REPORT_CRITERION_DUPLICATE(
		"REPORT_CRITERION_DUPLICATE",
		HttpStatus.CONFLICT,
		"같은 리포트 평가 기준이 이미 존재합니다."
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
