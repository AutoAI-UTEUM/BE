package io.edupilot.aiusage;

/**
 * TURN, DOC_CHAT, QUIZ_ASSESSMENT, DIAGNOSIS 호출부만 쿼터를 사전 검사한다.
 * 업로드 fan-out과 채점·리포트 같은 배치성 기능은 기록만 남긴다.
 */
public enum AiFeature {
	TURN,
	DOC_CHAT,
	GRADE,
	QUIZ_ASSESSMENT,
	DIAGNOSIS,
	REPORT,
	EXAM_DRAFT,
	OUTLINE,
	CAPTIONS,
	CRITERIA,
	EXTRACT
}
