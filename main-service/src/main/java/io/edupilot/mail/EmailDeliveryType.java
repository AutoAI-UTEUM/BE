package io.edupilot.mail;

public enum EmailDeliveryType {
	PASSWORD_RESET,
	EMAIL_VERIFY,
	NOTIFICATION,
	TEST,
	/** 팀 확인 안내 사본 정리용 구분이며 일반 메일 저장·발송에서는 지원하지 않습니다. */
	GUARDIAN_TEAM_NOTICE
}
