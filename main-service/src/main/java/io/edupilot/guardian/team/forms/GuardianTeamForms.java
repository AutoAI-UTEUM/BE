package io.edupilot.guardian.team.forms;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Plain text only. Rendering an 안내 or 회신 양식 never verifies a guardian or changes a request. */
public final class GuardianTeamForms {

	private static final DateTimeFormatter KOREAN_TIME = DateTimeFormatter
		.ofPattern("uuuu-MM-dd HH:mm:ss '(한국 시간)'", Locale.KOREAN)
		.withZone(ZoneId.of("Asia/Seoul"));
	private static final String REQUIRED_SCOPE = "SERVICE";
	private static final String OPTIONAL_SCOPE = "EXTERNAL_AI";
	private static final String REQUIRED_LABEL = "서비스 이용에 필요한 개인정보 처리";
	private static final String OPTIONAL_LABEL = "외부 AI에 학습 내용을 전송하는 선택 동의";
	private static final List<String> FORM_KEYS = List.of("receipt", "consent_email", "reply_form",
		"review_pending", "approved", "rejected", "needs_information", "revoked", "expired", "reviewer_checklist");

	private GuardianTeamForms() { }

	/**
	 * The configured notice text must be approved separately before activation. Presence validation here is
	 * not legal review. No child name, birthdate, address, reply body or bearer token is accepted.
	 */
	public record FormContext(String requestId, long generation, String noticeVersion, String noticeDigest, String noticeUrl,
		String collectionItemsText, String purposesText, String retentionText, String refusalText,
		String replyContact, String replyChannel, List<String> requiredScopes, String optionalAiScope,
		String optionalConsentText, Instant requestExpiresAt) {

		public FormContext {
			requestId = GuardianTeamForms.requestId(requestId);
			if (generation <= 0) {
				throw invalid("generation");
			}
			noticeVersion = singleLine(noticeVersion, "noticeVersion", 100);
			if (!noticeVersion.matches("[A-Za-z0-9_.-]{1,100}")) {
				throw invalid("noticeVersion");
			}
			noticeDigest = singleLine(noticeDigest, "noticeDigest", 64).toLowerCase(Locale.ROOT);
			if (!noticeDigest.matches("[a-f0-9]{64}")) {
				throw invalid("noticeDigest");
			}
			noticeUrl = httpsNoticeUrl(noticeUrl);
			collectionItemsText = noticeText(collectionItemsText, "collectionItemsText");
			purposesText = noticeText(purposesText, "purposesText");
			retentionText = noticeText(retentionText, "retentionText");
			refusalText = noticeText(refusalText, "refusalText");
			replyContact = singleLine(replyContact, "replyContact", 320);
			replyChannel = singleLine(replyChannel, "replyChannel", 30);
			if (!List.of("EMAIL_REPLY", "PHONE_CALLBACK").contains(replyChannel)) {
				throw invalid("replyChannel");
			}
			if (replyChannel.equals("EMAIL_REPLY") && !replyContact.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")) {
				throw invalid("replyContact");
			}
			if (requiredScopes == null || !requiredScopes.equals(List.of(REQUIRED_SCOPE))) {
				throw invalid("requiredScopes");
			}
			requiredScopes = List.copyOf(requiredScopes);
			if (optionalAiScope != null && !optionalAiScope.isBlank()) {
				optionalAiScope = singleLine(optionalAiScope, "optionalAiScope", 30);
				if (!optionalAiScope.equals(OPTIONAL_SCOPE)) {
					throw invalid("optionalAiScope");
				}
				optionalConsentText = noticeText(optionalConsentText, "optionalConsentText");
			} else {
				optionalAiScope = null;
				if (optionalConsentText != null && !optionalConsentText.isBlank()) {
					throw invalid("optionalConsentText");
				}
				optionalConsentText = null;
			}
			if (requestExpiresAt == null) {
				throw invalid("requestExpiresAt");
			}
			try {
				KOREAN_TIME.format(requestExpiresAt);
			} catch (RuntimeException exception) {
				throw invalid("requestExpiresAt");
			}
		}

		public String childLabel() {
			return "신청번호 " + requestId + "의 이용자";
		}

		@Override
		public String toString() {
			return "GuardianTeamFormContext[REDACTED]";
		}
	}

	/** For an authorized reviewer preview only; public endpoints must return the applicable form. */
	public static Map<String, String> renderAll(FormContext context) {
		Map<String, String> forms = new LinkedHashMap<>();
		for (String key : FORM_KEYS) {
			forms.put(key, render(key, context));
		}
		return Collections.unmodifiableMap(forms);
	}

	public static String render(String formKey, FormContext context) {
		if (context == null) {
			throw invalid("context");
		}
		if (formKey == null) {
			throw invalid("formKey");
		}
		return switch (formKey) {
			case "receipt" -> receipt(context);
			case "consent_email" -> consentEmail(context);
			case "reply_form" -> replyForm(context);
			case "review_pending" -> reviewPending(context);
			case "approved" -> approved(context);
			case "rejected" -> rejected(context);
			case "needs_information" -> needsInformation(context);
			case "revoked" -> revoked(context);
			case "expired" -> expired(context);
			case "reviewer_checklist" -> reviewerChecklist(context);
			default -> throw invalid("formKey");
		};
	}

	public static String consentEmail(FormContext context) {
		if (context == null) {
			throw invalid("context");
		}
		return "보호자 동의 확인 안내\n\n" + identity(context)
			+ "\n이 안내는 보호자 동의와 담당자 확인을 위한 것입니다. 링크 열기, 웹 체크 또는 회신 접수만으로 "
			+ "법정대리인 관계가 확인되거나 신청이 승인되지는 않습니다.\n\n"
			+ notice(context) + "\n\n" + confirmationChannel(context) + "\n\n" + replyForm(context)
			+ "\n\n주민등록번호, 신분증·여권 사진, 가족관계증명서 원본을 이 양식에 첨부하지 마세요. "
			+ "정해진 확인 절차로 판단하기 어려운 경우 담당자가 별도로 안내합니다.\n"
			+ "신청번호나 신청 차수가 다른 회신, 빠진 항목 또는 동의문 버전이 다른 회신은 담당자가 확인합니다. "
			+ "문자열이나 첨부파일만으로 자동 승인하지 않습니다.";
	}

	public static String replyForm(FormContext context) {
		if (context == null) {
			throw invalid("context");
		}
		String optional = context.optionalAiScope() == null
			? "선택 동의: 이 신청에는 별도 선택 동의 항목이 없습니다."
			: "선택 동의(EXTERNAL_AI): [동의합니다 / 동의하지 않습니다 중 직접 선택]\n"
				+ "선택 항목: " + OPTIONAL_LABEL + "\n"
				+ "필수 동의와 별도로 선택해 주세요. 선택 동의를 하지 않은 범위에는 동의가 있었다고 기록하지 않습니다.";
		return "복사해서 작성할 회신 양식\n"
			+ "신청번호: " + context.requestId() + "\n"
			+ "신청 차수: " + context.generation() + "\n"
			+ "대상 아동: " + context.childLabel() + "\n"
			+ "법정대리인 성명: [직접 입력]\n"
			+ "관계: [부모 / 미성년후견인 중 직접 선택]\n"
			+ "법정대리인 자기 확인: [위 아동의 법정대리인임을 확인합니다 / 확인하지 않습니다 중 직접 선택]\n"
			+ "동의문 버전: " + context.noticeVersion() + "\n"
			+ "동의문 SHA-256: " + context.noticeDigest() + "\n"
			+ "필수 동의(SERVICE): [동의합니다 / 동의하지 않습니다 중 직접 선택]\n"
			+ "필수 항목: " + REQUIRED_LABEL + "\n"
			+ optional + "\n"
			+ (context.replyChannel().equals("EMAIL_REPLY")
				? "회신 주소를 확인 연락처로 사용합니다. 별도 주소나 전화번호를 이 양식에 추가하지 않아도 됩니다."
				: "담당자 전화 확인에서 위 항목과 선택 내용을 확인합니다. 이 양식을 작성한 사실만으로 전화 확인을 대신하지 않습니다.");
	}

	private static String receipt(FormContext context) {
		return "보호자 확인 신청이 접수되었습니다.\n\n" + identity(context)
			+ "\n담당자 검토와 필요한 동의 확인이 끝날 때까지 보호자 확인이 필요한 이용은 제한됩니다. "
			+ "접수는 승인이나 법정대리인 관계 확인을 뜻하지 않습니다.\n"
			+ "연락 창구: " + context.replyContact() + "\n"
			+ "신청 확인 기한: " + KOREAN_TIME.format(context.requestExpiresAt()) + "\n"
			+ "보호자 정보의 보유·파기 기준: " + context.retentionText();
	}

	private static String reviewPending(FormContext context) {
		return "보호자 확인 자료를 접수해 담당자 검토를 기다리고 있습니다.\n\n" + identity(context)
			+ "\n회신 또는 웹 의사 표시를 접수한 사실만으로 승인되지 않습니다. "
			+ "담당자가 정해진 절차에 따라 동의 내용과 관계 확인 자료를 검토합니다.\n"
			+ "중복 회신은 새 승인으로 처리하지 않습니다. "
			+ "재발급이나 보완 요청으로 최초 수집일과 파기 기한이 연장되지는 않습니다.\n"
			+ "신청 확인 기한: " + KOREAN_TIME.format(context.requestExpiresAt());
	}

	private static String approved(FormContext context) {
		return "담당자 검토 결과, 보호자 확인 신청이 승인되었습니다.\n\n" + identity(context)
			+ "\n승인된 동의 범위와 다른 가입·이용 조건을 충족하면 해당 기능을 이용할 수 있습니다. "
			+ "선택 동의를 하지 않은 범위에는 동의가 있었다고 처리하지 않습니다.\n"
			+ "동의 내용 확인: " + context.noticeUrl() + "\n"
			+ "동의 철회 또는 기록 오류 문의: " + context.replyContact() + "\n"
			+ "동의 증거의 보유·파기 기준: " + context.retentionText();
	}

	private static String rejected(FormContext context) {
		return "담당자 검토 결과, 보호자 확인 신청이 반려되었습니다.\n\n" + identity(context)
			+ "\n승인된 보호자 확인으로 처리하지 않으며, 보호자 확인이 필요한 이용 제한은 계속됩니다. "
			+ "반려 사유는 별도로 안내된 내용을 확인해 주세요.\n"
			+ "문의: " + context.replyContact() + "\n"
			+ "보호자 정보의 보유·파기 기준: " + context.retentionText();
	}

	private static String needsInformation(FormContext context) {
		return "보호자 확인 신청에 보완이 필요합니다.\n\n" + identity(context)
			+ "\n담당자가 요청한 누락 항목 또는 확인 내용을 안내된 연락 창구로 전달해 주세요. "
			+ "불필요한 개인정보나 원본 신분 증거를 추가하지 마세요.\n"
			+ "보완 접수는 승인으로 처리하지 않습니다. "
			+ "보완 요청이나 링크 재발급은 최초 수집일과 파기 기한을 늘리지 않습니다.\n"
			+ "연락 창구: " + context.replyContact() + "\n"
			+ "신청 확인 기한: " + KOREAN_TIME.format(context.requestExpiresAt());
	}

	private static String revoked(FormContext context) {
		return "보호자 확인 신청 또는 해당 승인에 대한 철회가 처리되었습니다.\n\n" + identity(context)
			+ "\n철회된 범위에서 보호자 동의를 근거로 한 이용과 외부 AI 전달을 계속하지 않습니다. "
			+ "새 승인 여부는 별도 확인 절차를 거쳐 결정합니다.\n"
			+ "철회 내용 또는 기록 오류 문의: " + context.replyContact() + "\n"
			+ "관련 정보의 보유·파기 기준: " + context.retentionText();
	}

	private static String expired(FormContext context) {
		return "보호자 확인 신청 또는 승인의 유효기간이 지났습니다.\n\n" + identity(context)
			+ "\n이 신청의 링크와 회신으로 승인 절차를 계속할 수 없습니다. "
			+ "필요한 경우 새 신청 절차를 안내받아 주세요. "
			+ "링크 재발급만으로 최초 수집일과 파기 기한을 늘릴 수는 없습니다.\n"
			+ "연락 창구: " + context.replyContact() + "\n"
			+ "보호자 정보의 보유·파기 기준: " + context.retentionText();
	}

	private static String reviewerChecklist(FormContext context) {
		return "담당자 검토 체크리스트 · 기록 양식\n\n" + identity(context) + "\n"
			+ "1. 현재 담당자 권한과 해당 신청의 상태·버전·최초 수집일·확인 기한을 서버 기록에서 확인합니다.\n"
			+ "2. 신청번호와 신청 차수, 동의문 버전·digest, 대상 이용자가 현재 신청을 가리키는지 확인합니다. 같은 번호의 과거 차수 회신은 재사용하지 않습니다.\n"
			+ "3. 현재 설정과 일치하는 확인 수단·시각·최소 참조를 확인합니다.\n"
			+ "4. 성명, 부모/미성년후견인 관계, 법정대리인 자기 확인과 필수·선택 동의가 구분되어 있는지 확인합니다.\n"
			+ "5. 정해진 관계 확인 기준으로 판단합니다. 자기 선언, 이메일 주소 소유 또는 웹 클릭만으로 관계를 단정하지 않습니다.\n"
			+ "6. 누락·불일치·분쟁은 보완 요청 또는 반려 사유로 기록합니다. 본문 문자열만으로 승인하지 않습니다.\n"
			+ "7. 거절·미확인 보호자 정보의 별도 파기 조건과 승인 후 증거 보존 설정을 확인합니다. 일반 PDF·렌더 30일을 적용하지 않습니다.\n"
			+ "8. 승인·반려·보완 요청·철회는 신청 버전과 멱등성 키를 넣어 제출합니다. 현재 권한과 상태는 결정 트랜잭션에서도 다시 확인합니다.\n\n"
			+ "최소 기록 항목\n"
			+ "신청번호: " + context.requestId() + "\n"
			+ "신청 차수: " + context.generation() + "\n"
			+ "확인 수단: " + (context.replyChannel().equals("EMAIL_REPLY")
				? "명시적 이메일 회신 (EMAIL_REPLY)" : "담당자 전화 확인 (PHONE)") + "\n"
			+ "증거 참조: [원문을 복사하지 않은 제한된 참조]\n"
			+ "실제 회신 수신·전화 동의 시각: [직접 입력]\n"
			+ "관계 확인 기준 버전: [현재 승인된 기준]\n"
			+ "검토 결과와 사유: [승인 / 반려 / 보완 요청 / 철회 및 최소 사유]\n"
			+ "담당자·처리 시각·요청 세대번호: 서버 기록으로 남깁니다.\n"
			+ "감사 기록에는 회신 본문, 전화번호, 신분증 이미지 또는 토큰을 넣지 않습니다.";
	}

	private static String identity(FormContext context) {
		return "신청번호: " + context.requestId() + "\n신청 차수: " + context.generation()
			+ "\n대상 아동: " + context.childLabel()
			+ "\n동의문 버전: " + context.noticeVersion() + "\n동의문 SHA-256: " + context.noticeDigest();
	}

	private static String notice(FormContext context) {
		String optional = context.optionalAiScope() == null
			? "선택 동의 안내\n이 신청에는 별도 선택 동의 항목이 없습니다."
			: "선택 동의 안내 · " + OPTIONAL_LABEL + " (EXTERNAL_AI)\n" + context.optionalConsentText();
		return "개인정보 처리 및 동의 안내\n"
			+ "수집 항목\n" + context.collectionItemsText() + "\n\n"
			+ "수집·이용 목적\n" + context.purposesText() + "\n\n"
			+ "보유·파기 기준\n" + context.retentionText() + "\n\n"
			+ "동의 거부와 이용 영향\n" + context.refusalText() + "\n\n"
			+ "필수 동의 항목 · " + REQUIRED_LABEL + " (SERVICE)\n"
			+ "위 안내의 항목과 목적을 읽은 뒤 필수 동의 여부를 명시해 주세요.\n\n"
			+ optional + "\n\n동의문 전체: " + context.noticeUrl()
			+ "\n신청 확인 기한: " + KOREAN_TIME.format(context.requestExpiresAt());
	}

	private static String confirmationChannel(FormContext context) {
		if (context.replyChannel().equals("EMAIL_REPLY")) {
			return "확인 방법: 명시적 이메일 회신\n"
				+ "아래 양식을 복사해 직접 작성한 뒤 " + context.replyContact() + " 주소로 회신해 주세요. "
				+ "선택 문구를 그대로 두지 말고 본인의 성명·관계와 동의 여부를 명확히 적어 주세요. "
				+ "회신을 받은 담당자가 정해진 절차로 최종 판단합니다.";
		}
		return "확인 방법: 담당자 전화 확인\n"
			+ "연락 창구: " + context.replyContact() + "\n"
			+ "담당자가 동의 내용을 안내하고 성명·관계·법정대리인 자기 확인과 동의 여부를 확인합니다. "
			+ "아래 양식은 확인할 내용을 미리 읽기 위한 것입니다. "
			+ "웹 체크나 양식 작성만으로 전화 확인 또는 최종 승인을 대신하지 않습니다.";
	}

	private static String requestId(String value) {
		String result = singleLine(value, "requestId", 36);
		if (!result.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
			throw invalid("requestId");
		}
		return UUID.fromString(result).toString();
	}

	private static String httpsNoticeUrl(String value) {
		String result = singleLine(value, "noticeUrl", 2048);
		try {
			URI uri = URI.create(result);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getRawQuery() != null || uri.getRawFragment() != null) {
				throw invalid("noticeUrl");
			}
		} catch (RuntimeException exception) {
			throw invalid("noticeUrl");
		}
		return result;
	}

	private static String noticeText(String value, String field) {
		if (value == null) {
			throw invalid(field);
		}
		String result = value.replace("\r\n", "\n").strip();
		if (result.isEmpty() || result.length() > 4096 || placeholder(result)
			|| result.codePoints().anyMatch(code -> unsafeControl(code) && code != '\n' && code != '\t')) {
			throw invalid(field);
		}
		return result;
	}

	private static String singleLine(String value, String field, int maxLength) {
		if (value == null) {
			throw invalid(field);
		}
		String result = value.strip();
		if (result.isEmpty() || result.length() > maxLength || placeholder(result)
			|| result.codePoints().anyMatch(GuardianTeamForms::unsafeControl)) {
			throw invalid(field);
		}
		return result;
	}

	private static boolean unsafeControl(int code) {
		return Character.isISOControl(code) || Character.getType(code) == Character.FORMAT
			|| code == 0x2028 || code == 0x2029;
	}

	private static boolean placeholder(String value) {
		String normalized = value.toLowerCase(Locale.ROOT);
		return normalized.matches("(?s).*\\b(tbd|todo)\\b.*") || normalized.contains("placeholder")
			|| normalized.startsWith("[") && normalized.endsWith("]") || normalized.contains("미정")
			|| normalized.contains("미확정") || normalized.contains("추후 확정")
			|| normalized.contains("[미승인") || normalized.equals("미승인") || normalized.contains("예시 전용")
			|| normalized.contains("[운영 입력") || normalized.contains("not approved")
			|| normalized.contains("not configured");
	}

	private static IllegalArgumentException invalid(String field) {
		return new IllegalArgumentException("Guardian TEAM_REVIEW form configuration is incomplete or invalid: " + field);
	}
}
