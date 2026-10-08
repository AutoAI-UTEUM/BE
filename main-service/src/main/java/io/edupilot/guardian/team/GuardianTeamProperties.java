package io.edupilot.guardian.team;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** No verification provider, reviewer grant, legal notice, or retention period is inferred. */
@ConfigurationProperties("edupilot.guardian.team")
public record GuardianTeamProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("false") boolean policyConfirmed,
	String portalBaseUrl,
	String noticeVersion,
	String noticeDigest,
	String noticeUrl,
	String collectionItemsText,
	String purposesText,
	String retentionText,
	String refusalText,
	String replyContact,
	String replyChannel,
	List<Long> reviewerIds,
	List<String> requiredScopes,
	String optionalAiScope,
	String optionalConsentText,
	Duration linkTtl,
	Duration requestTtl,
	Duration approvedEvidenceRetention,
	Duration approvalValidity
) {
	public GuardianTeamProperties {
		reviewerIds = reviewerIds == null ? List.of() : List.copyOf(reviewerIds);
		requiredScopes = requiredScopes == null ? List.of() : List.copyOf(requiredScopes);
	}

	public boolean ready() {
		return enabled && policyConfirmed && https(portalBaseUrl) && https(noticeUrl)
			&& noticeVersion != null && noticeVersion.matches("[A-Za-z0-9_.-]{1,100}")
			&& noticeDigest != null && noticeDigest.matches("[0-9a-f]{64}")
			&& text(collectionItemsText) && text(purposesText) && text(retentionText) && text(refusalText)
			&& text(replyContact) && validReplyChannel() && !reviewerIds.isEmpty() && reviewerIds.size() <= 100
			&& reviewerIds.stream().allMatch(id -> id != null && id > 0)
			&& reviewerIds.stream().distinct().count() == reviewerIds.size()
			&& requiredScopes.equals(List.of("SERVICE")) && optionalAiReady()
			&& bounded(linkTtl, Duration.ofHours(24)) && bounded(requestTtl, Duration.ofDays(5))
			&& linkTtl.compareTo(requestTtl) <= 0 && positive(approvedEvidenceRetention) && positive(approvalValidity)
			&& approvedEvidenceRetention.compareTo(approvalValidity) >= 0;
	}

	public void validateActivation() {
		if (enabled && !ready()) {
			throw new IllegalStateException("보호자 팀 확인을 활성화하려면 검토된 동의문, 담당자, 기간과 확인 창구를 모두 설정해야 합니다.");
		}
	}

	/** Approval and pending requests bind to the complete reviewed notice/policy snapshot. */
	public String configurationDigest() {
		Object[] values = {noticeVersion, noticeDigest, noticeUrl, collectionItemsText, purposesText, retentionText,
			refusalText, replyContact, replyChannel, requiredScopes, optionalAiScope, optionalConsentText,
			approvedEvidenceRetention, approvalValidity};
		StringBuilder canonical = new StringBuilder();
		for (Object value : values) {
			String text = Objects.toString(value, ""); canonical.append(text.length()).append(':').append(text);
		}
		return GuardianTeamSecrets.hash(canonical.toString());
	}

	private boolean validReplyChannel() {
		if (replyContact.codePoints().anyMatch(code -> Character.isISOControl(code) || Character.getType(code) == Character.FORMAT)) { return false; }
		return "EMAIL_REPLY".equals(replyChannel)
			? replyContact.matches("[^\\s@<>]{1,64}@[^\\s@<>]{1,190}\\.[^\\s@<>]{2,63}")
			: "PHONE_CALLBACK".equals(replyChannel) && replyContact.length() <= 200;
	}
	private boolean optionalAiReady() {
		if (optionalAiScope == null || optionalAiScope.isBlank()) { return optionalConsentText == null || optionalConsentText.isBlank(); }
		return "EXTERNAL_AI".equals(optionalAiScope) && text(optionalConsentText);
	}
	private static boolean text(String value) {
		if (value == null || value.isBlank() || value.length() > 4000) { return false; }
		String upper = value.toUpperCase(Locale.ROOT);
		return !upper.contains("TBD") && !upper.contains("TODO") && !upper.contains("PLACEHOLDER")
			&& !upper.contains("NOT APPROVED") && !upper.contains("NOT CONFIGURED")
			&& !value.contains("미정") && !value.contains("미확정") && !value.contains("추후 입력") && !value.contains("추후 확정")
			&& !value.contains("[미승인") && !value.contains("[운영 입력") && !value.contains("예시 전용")
			&& value.codePoints().noneMatch(code -> (Character.isISOControl(code) && code != '\n' && code != '\t')
				|| Character.getType(code) == Character.FORMAT || code == 0x2028 || code == 0x2029);
	}
	private static boolean positive(Duration value) { return value != null && !value.isZero() && !value.isNegative(); }
	private static boolean bounded(Duration value, Duration maximum) { return positive(value) && value.compareTo(maximum) <= 0; }
	private static boolean https(String value) {
		if (value == null || value.length() > 2000) { return false; }
		try {
			URI uri = URI.create(value);
			return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
				&& uri.getRawQuery() == null && uri.getRawFragment() == null;
		} catch (IllegalArgumentException invalid) { return false; }
	}
}
