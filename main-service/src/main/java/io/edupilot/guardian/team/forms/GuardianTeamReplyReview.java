package io.edupilot.guardian.team.forms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.edupilot.guardian.team.forms.GuardianTeamForms.FormContext;

/**
 * Optional, bounded assistance for a reviewer pasting a plain text reply. This does not receive mail,
 * verify its sender, persist the body, decide consent, or perform an approval.
 */
public final class GuardianTeamReplyReview {

	private static final int MAX_BODY_CHARACTERS = 8192;
	private static final int MAX_FIELD_CHARACTERS = 320;
	private static final List<String> REQUIRED_FIELDS = List.of("신청번호", "신청 차수", "법정대리인 성명", "관계",
		"법정대리인 자기 확인", "동의문 버전", "동의문 SHA-256", "필수 동의(SERVICE)");
	private static final String OPTIONAL_FIELD = "선택 동의(EXTERNAL_AI)";

	private GuardianTeamReplyReview() { }

	public record Review(Map<String, String> suppliedFields, List<String> missingFields, List<String> manualChecks) {
		public Review {
			suppliedFields = Collections.unmodifiableMap(new LinkedHashMap<>(suppliedFields));
			missingFields = List.copyOf(missingFields);
			manualChecks = List.copyOf(manualChecks);
		}

		/** Completeness is assistance, never a guardian verification or approval result. */
		public boolean requiresHumanReview() {
			return true;
		}

		@Override
		public String toString() {
			return "GuardianTeamReplyReview[REDACTED]";
		}
	}

	public static Review inspect(String plainText, FormContext context) {
		if (context == null || plainText == null || plainText.length() > MAX_BODY_CHARACTERS
			|| plainText.codePoints().anyMatch(code -> (Character.isISOControl(code)
				&& code != '\r' && code != '\n' && code != '\t') || Character.getType(code) == Character.FORMAT)) {
			throw new IllegalArgumentException("보호자 회신은 제한된 길이의 일반 텍스트로만 검토할 수 있습니다.");
		}
		Map<String, String> fields = new LinkedHashMap<>();
		List<String> checks = new ArrayList<>();
		for (String line : plainText.replace("\r\n", "\n").split("\n")) {
			String trimmed = line.strip();
			// Quoted earlier notices are not a new, explicit reply.
			if (trimmed.startsWith(">")) {
				continue;
			}
			int separator = trimmed.indexOf(':');
			if (separator < 0) {
				continue;
			}
			String label = trimmed.substring(0, separator).strip();
			if (!REQUIRED_FIELDS.contains(label) && !(context.optionalAiScope() != null && label.equals(OPTIONAL_FIELD))) {
				continue;
			}
			String value = trimmed.substring(separator + 1).strip();
			if (value.isEmpty() || value.startsWith("[") && value.endsWith("]")) {
				continue;
			}
			if (value.length() > MAX_FIELD_CHARACTERS || value.indexOf('\r') >= 0 || value.indexOf('\t') >= 0) {
				checks.add(label + ": 길이 또는 형식을 직접 확인해 주세요.");
				continue;
			}
			if (fields.putIfAbsent(label, value) != null) {
				checks.add(label + ": 같은 항목이 여러 번 나타납니다. 담당자가 명시적 회신 내용을 확인해야 합니다.");
			}
		}
		List<String> missing = REQUIRED_FIELDS.stream().filter(label -> !fields.containsKey(label)).toList();
		match(fields, "신청번호", context.requestId(), false, checks);
		match(fields, "신청 차수", Long.toString(context.generation()), false, checks);
		match(fields, "동의문 버전", context.noticeVersion(), false, checks);
		match(fields, "동의문 SHA-256", context.noticeDigest(), true, checks);
		if (fields.containsKey("관계") && !List.of("부모", "미성년후견인").contains(fields.get("관계"))) {
			checks.add("관계: 부모 또는 미성년후견인인지 정해진 절차로 확인해 주세요.");
		}
		if (fields.containsKey("법정대리인 자기 확인")
			&& !fields.get("법정대리인 자기 확인").equals("위 아동의 법정대리인임을 확인합니다")) {
			checks.add("법정대리인 자기 확인: 명시적 자기 확인이 있는지 담당자가 확인해 주세요.");
		}
		if (fields.containsKey("필수 동의(SERVICE)") && !fields.get("필수 동의(SERVICE)").equals("동의합니다")) {
			checks.add("필수 동의(SERVICE): 거절 또는 불명확한 회신을 승인 동의로 처리하지 마세요.");
		}
		if (context.optionalAiScope() != null) {
			if (!fields.containsKey(OPTIONAL_FIELD)) {
				checks.add("선택 동의(EXTERNAL_AI): 회신이 비어 있습니다. 선택 동의가 있었다고 기록하지 마세요.");
			} else if (!List.of("동의합니다", "동의하지 않습니다").contains(fields.get(OPTIONAL_FIELD))) {
				checks.add("선택 동의(EXTERNAL_AI): 필수 동의와 구분된 명시적 선택인지 확인해 주세요.");
			}
		}
		checks.add("회신 발신자와 확인 수단의 최소 참조를 확인하세요. 이 도구는 이메일 수신이나 발신자 검증을 수행하지 않습니다.");
		checks.add("최초 수집일, 신청·토큰 만료, 현재 신청 차수·상태·버전과 담당자 권한을 서버 기록에서 확인하세요.");
		checks.add("법정대리인 관계와 최종 승인·반려는 담당자가 판단합니다. 누락 항목이 없어도 자동 승인하지 않습니다.");
		return new Review(fields, missing, checks);
	}

	private static void match(Map<String, String> fields, String label, String expected, boolean ignoreCase,
		List<String> checks) {
		String actual = fields.get(label);
		if (actual != null && !(ignoreCase ? actual.equalsIgnoreCase(expected) : actual.equals(expected))) {
			checks.add(label + ": 현재 신청 기록과 일치하지 않습니다. 담당자가 확인해 주세요.");
		}
	}
}
