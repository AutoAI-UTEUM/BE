package io.edupilot.guardian.team.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.edupilot.guardian.team.forms.GuardianTeamForms.FormContext;

class GuardianTeamFormsTest {
	private static final String REQUEST_ID = "b920343e-f30e-4ca1-9828-1088ad5bfe70";
	private static final String DIGEST = "a".repeat(64);
	private static final Instant DEADLINE = Instant.parse("2026-10-10T01:00:00Z");

	@Test
	void allFormsUseRequestReferenceWithoutCollectingAChildProfileOrBearerToken() {
		var forms = GuardianTeamForms.renderAll(context());
		assertThat(forms.keySet()).containsExactly("receipt", "consent_email", "reply_form", "review_pending",
			"approved", "rejected", "needs_information", "revoked", "expired", "reviewer_checklist");
		assertThat(forms.values()).allSatisfy(form -> assertThat(form)
			.contains(REQUEST_ID, "신청 차수: 1", "신청번호 " + REQUEST_ID + "의 이용자")
			.doesNotContain("생년월일:", "아동 성명:", "token=", "주민등록번호:"));
		assertThatThrownBy(() -> forms.put("extra", "value")).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void consentEmailIncludesConfiguredNoticeAndExplicitReplyInstructionsInsteadOfClaimingRelationshipVerification() {
		String email = GuardianTeamForms.consentEmail(context());
		assertThat(email).contains("합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향",
			"https://guardian.example.invalid/notice", "review@example.invalid", "명시적 이메일 회신",
			"법정대리인 관계가 확인되거나 신청이 승인되지는 않습니다", "문자열이나 첨부파일만으로 자동 승인하지 않습니다",
			"주민등록번호, 신분증·여권 사진, 가족관계증명서 원본을 이 양식에 첨부하지 마세요");
		assertThat(email).doesNotContain("인증업체", "OTP 입력", "문자 인증 완료", "전화 확인 절차를 이용할 수 있습니다",
			"확인 방법: 담당자 전화 확인");
		assertThat(GuardianTeamForms.render("reviewer_checklist", context()))
			.contains("확인 수단: 명시적 이메일 회신 (EMAIL_REPLY)").doesNotContain("확인 수단: 담당자 전화 확인");
	}

	@Test
	void replyFormSeparatesUnselectedMandatoryAndOptionalConsentAndAutofillsOnlyImmutableReferenceFields() {
		String reply = GuardianTeamForms.replyForm(context());
		assertThat(reply).contains("신청번호: " + REQUEST_ID, "신청 차수: 1", "동의문 버전: guardian-v1", "동의문 SHA-256: " + DIGEST,
			"법정대리인 성명: [직접 입력]", "관계: [부모 / 미성년후견인 중 직접 선택]",
			"필수 동의(SERVICE): [동의합니다 / 동의하지 않습니다 중 직접 선택]",
			"선택 동의(EXTERNAL_AI): [동의합니다 / 동의하지 않습니다 중 직접 선택]",
			"필수 동의와 별도로 선택해 주세요", "별도 주소나 전화번호를 이 양식에 추가하지 않아도 됩니다");
		assertThat(reply).doesNotContain("성명: 홍", "[x]", "[✓]", "신분증 첨부:", "생년월일:");
	}

	@Test
	void noOptionalScopeDoesNotInventAnAiConsentChoice() {
		var noOptional = create("합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향",
			"review@example.invalid", "EMAIL_REPLY", new ArrayList<>(List.of("SERVICE")), null, null,
			"guardian-v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE);
		assertThat(GuardianTeamForms.replyForm(noOptional)).contains("별도 선택 동의 항목이 없습니다")
			.doesNotContain("선택 동의(EXTERNAL_AI):");
	}

	@Test
	void phoneCallbackFormRequiresTheActualHumanConfirmationStep() {
		var phone = create("합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향",
			"팀 확인 연락 창구", "PHONE_CALLBACK", List.of("SERVICE"), "EXTERNAL_AI", "합성 선택 동의 안내",
			"guardian-v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE);
		assertThat(GuardianTeamForms.consentEmail(phone)).contains("확인 방법: 담당자 전화 확인",
			"웹 체크나 양식 작성만으로 전화 확인 또는 최종 승인을 대신하지 않습니다")
			.doesNotContain("주소로 회신해 주세요");
		assertThat(GuardianTeamForms.render("reviewer_checklist", phone))
			.contains("확인 수단: 담당자 전화 확인 (PHONE)").doesNotContain("확인 수단: 명시적 이메일 회신");
	}

	@Test
	void deadlineIsExplicitlyKoreanTimeAndReissueNeverPromisesExtendedCollectionRetention() {
		var forms = GuardianTeamForms.renderAll(context());
		assertThat(forms.get("receipt")).contains("2026-10-10 10:00:00 (한국 시간)");
		assertThat(forms.get("needs_information")).contains("최초 수집일과 파기 기한을 늘리지 않습니다");
		assertThat(forms.get("expired")).contains("최초 수집일과 파기 기한을 늘릴 수는 없습니다");
		assertThat(forms.get("reviewer_checklist")).contains("일반 PDF·렌더 30일을 적용하지 않습니다",
			"현재 권한과 상태는 결정 트랜잭션에서도 다시 확인", "감사 기록에는 회신 본문");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "TBD", "보존기간 미정", "[운영 입력 필요]", "예시 전용 문구", "not approved"})
	void incompleteOrUnapprovedNoticeCannotProduceALiveForm(String value) {
		assertThatThrownBy(() -> create(value, "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향",
			"review@example.invalid", "EMAIL_REPLY", List.of("SERVICE"), "EXTERNAL_AI", "합성 선택 동의 안내",
			"guardian-v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("collectionItemsText")
			.hasMessageNotContaining(value.isBlank() ? "never-reflected-sensitive-value" : value);
	}

	@Test
	void purposeRetentionRefusalAndOptionalNoticeAreRequiredIndependently() {
		assertInvalid("purposesText", "합성 수집 항목", "", "합성 보유·파기 기준", "합성 거부 영향", "합성 선택 동의 안내");
		assertInvalid("retentionText", "합성 수집 항목", "합성 처리 목적", "", "합성 거부 영향", "합성 선택 동의 안내");
		assertInvalid("refusalText", "합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "", "합성 선택 동의 안내");
		assertInvalid("optionalConsentText", "합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향", "");
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://guardian.example.invalid/notice", "https://user:pass@guardian.example.invalid/notice",
		"https://guardian.example.invalid/notice?token=private", "https://guardian.example.invalid/notice#secret", "javascript:alert(1)"})
	void noticeReferenceCannotCarryUnsafeSchemesCredentialsOrSecretParameters(String noticeUrl) {
		assertThatThrownBy(() -> create("합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향",
			"review@example.invalid", "EMAIL_REPLY", List.of("SERVICE"), null, null,
			"guardian-v1", DIGEST, noticeUrl, REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("noticeUrl").hasMessageNotContaining(noticeUrl);
	}

	@Test
	void identifiersDigestAndReplyAddressMustBeUnambiguous() {
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "review@example.invalid\nBcc: hidden@example.invalid",
			"EMAIL_REPLY", List.of("SERVICE"), null, null, "v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replyContact").hasMessageNotContaining("hidden@example.invalid");
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "not-an-email", "EMAIL_REPLY",
			List.of("SERVICE"), null, null, "v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replyContact");
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "review@example.invalid", "EMAIL_REPLY",
			List.of("SERVICE"), null, null, "v1", "not-a-digest", "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("noticeDigest");
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "review@example.invalid", "EMAIL_REPLY",
			List.of("SERVICE"), null, null, "v1", DIGEST, "https://guardian.example.invalid/notice", "1-1-1-1-1", 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
	}

	@Test
	void unsupportedScopeOrAmbiguousOptionalWordingCannotInventAConsentContract() {
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "review@example.invalid", "EMAIL_REPLY",
			List.of("SERVICE", "EXTERNAL_AI"), null, null, "v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requiredScopes");
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "review@example.invalid", "EMAIL_REPLY",
			List.of("SERVICE"), "MARKETING", "선택 안내", "v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("optionalAiScope");
		assertThatThrownBy(() -> create("항목", "목적", "보유 기준", "거부 영향", "review@example.invalid", "EMAIL_REPLY",
			List.of("SERVICE"), null, "외부 AI 안내", "v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("optionalConsentText");
	}

	@Test
	void contextDefensivelyCopiesScopesAndRedactsItsStringRepresentation() {
		var scopes = new ArrayList<>(List.of("SERVICE"));
		var context = create("항목", "목적", "보유 기준", "거부 영향", "private-review@example.invalid", "EMAIL_REPLY",
			scopes, null, null, "v1", DIGEST, "https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE);
		scopes.add("EXTERNAL_AI");
		assertThat(context.requiredScopes()).containsExactly("SERVICE");
		assertThatThrownBy(() -> context.requiredScopes().add("OTHER")).isInstanceOf(UnsupportedOperationException.class);
		assertThat(context.toString()).doesNotContain("private-review@example.invalid", "항목", REQUEST_ID, DIGEST).contains("REDACTED");
	}

	@Test
	void oversizedOrInvisibleControlWordingCannotHideTerms() {
		assertInvalid("collectionItemsText", "항목".repeat(3000), "목적", "보유 기준", "거부 영향", "선택 안내");
		assertInvalid("collectionItemsText", "항목\u202e숨겨진 안내", "목적", "보유 기준", "거부 영향", "선택 안내");
		assertInvalid("collectionItemsText", "항목\u0000추가", "목적", "보유 기준", "거부 영향", "선택 안내");
	}

	@Test
	void onlyKnownApplicableFormsCanBeRendered() {
		assertThat(GuardianTeamForms.render("review_pending", context())).contains("담당자 검토를 기다리고 있습니다");
		assertThatThrownBy(() -> GuardianTeamForms.render("unknown", context())).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GuardianTeamForms.render("receipt", null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void reapplicationWithSameRequestIdShowsItsNewGenerationAndRejectsNonpositiveGeneration() {
		var current = context(2L);
		assertThat(current.requestId()).isEqualTo(context().requestId());
		assertThat(GuardianTeamForms.consentEmail(current)).contains("신청 차수: 2").doesNotContain("신청 차수: 1\n");
		assertThat(GuardianTeamForms.replyForm(current)).contains("신청 차수: 2");
		assertThat(GuardianTeamForms.render("reviewer_checklist", current))
			.contains("신청 차수: 2", "같은 번호의 과거 차수 회신은 재사용하지 않습니다");
		assertThatThrownBy(() -> context(0L)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("generation");
		assertThatThrownBy(() -> context(-1L)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("generation");
	}

	static FormContext context() {
		return context(1L);
	}

	static FormContext context(long generation) {
		return create("합성 수집 항목", "합성 처리 목적", "합성 보유·파기 기준", "합성 거부 영향", "review@example.invalid",
			"EMAIL_REPLY", List.of("SERVICE"), "EXTERNAL_AI", "합성 선택 동의 안내", "guardian-v1", DIGEST,
			"https://guardian.example.invalid/notice", REQUEST_ID, generation, DEADLINE);
	}

	private void assertInvalid(String field, String items, String purposes, String retention, String refusal, String optionalText) {
		assertThatThrownBy(() -> create(items, purposes, retention, refusal, "review@example.invalid", "EMAIL_REPLY",
			List.of("SERVICE"), "EXTERNAL_AI", optionalText, "guardian-v1", DIGEST,
			"https://guardian.example.invalid/notice", REQUEST_ID, 1L, DEADLINE))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
	}

	private static FormContext create(String items, String purposes, String retention, String refusal, String replyContact,
		String replyChannel, List<String> requiredScopes, String optionalScope, String optionalText, String version,
		String digest, String noticeUrl, String requestId, long generation, Instant deadline) {
		return new FormContext(requestId, generation, version, digest, noticeUrl, items, purposes, retention, refusal, replyContact,
			replyChannel, requiredScopes, optionalScope, optionalText, deadline);
	}
}
