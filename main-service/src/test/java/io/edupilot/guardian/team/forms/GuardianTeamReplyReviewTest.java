package io.edupilot.guardian.team.forms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GuardianTeamReplyReviewTest {

	@Test
	void completeExplicitReplyStillRequiresSenderRelationshipAndHumanDecisionChecks() {
		var result = GuardianTeamReplyReview.inspect(completeReply(), GuardianTeamFormsTest.context());
		assertThat(result.missingFields()).isEmpty();
		assertThat(result.requiresHumanReview()).isTrue();
		assertThat(result.manualChecks()).anySatisfy(check -> assertThat(check).contains("발신자 검증을 수행하지 않습니다"))
			.anySatisfy(check -> assertThat(check).contains("누락 항목이 없어도 자동 승인하지 않습니다"));
		assertThat(result.toString()).doesNotContain("합성 보호자", "동의합니다").contains("REDACTED");
	}

	@Test
	void unfilledOrQuotedNoticeIsNotMistakenForAnExplicitReply() {
		String form = GuardianTeamForms.replyForm(GuardianTeamFormsTest.context());
		var unfilled = GuardianTeamReplyReview.inspect(form, GuardianTeamFormsTest.context());
		assertThat(unfilled.missingFields()).contains("법정대리인 성명", "관계", "법정대리인 자기 확인", "필수 동의(SERVICE)");
		String quoted = "> " + completeReply().replace("\n", "\n> ");
		var onlyQuote = GuardianTeamReplyReview.inspect(quoted, GuardianTeamFormsTest.context());
		assertThat(onlyQuote.suppliedFields()).isEmpty();
		assertThat(onlyQuote.missingFields()).hasSize(8);
	}

	@Test
	void noticeOrRequestMismatchAndAmbiguousDuplicatesAreFlaggedWithoutOverwritingEarlierValues() {
		String reply = completeReply().replace("동의문 버전: guardian-v1", "동의문 버전: older-v0")
			+ "\n신청번호: unrelated-request";
		var result = GuardianTeamReplyReview.inspect(reply, GuardianTeamFormsTest.context());
		assertThat(result.manualChecks()).anySatisfy(check -> assertThat(check).contains("동의문 버전: 현재 신청 기록과 일치하지 않습니다"))
			.anySatisfy(check -> assertThat(check).contains("신청번호: 같은 항목이 여러 번 나타납니다"));
		assertThat(result.suppliedFields().get("신청번호")).isEqualTo(GuardianTeamFormsTest.context().requestId());
		assertThat(result.requiresHumanReview()).isTrue();
	}

	@Test
	void replyFromPriorGenerationWithSameRequestIdAndNoticeRequiresManualRejectionOfTheMismatch() {
		var current = GuardianTeamFormsTest.context(2L);
		var oldReply = GuardianTeamReplyReview.inspect(completeReply(), current);
		assertThat(oldReply.missingFields()).isEmpty();
		assertThat(oldReply.suppliedFields().get("신청번호")).isEqualTo(current.requestId());
		assertThat(oldReply.manualChecks()).anySatisfy(check -> assertThat(check)
			.contains("신청 차수: 현재 신청 기록과 일치하지 않습니다"));
		assertThat(oldReply.requiresHumanReview()).isTrue();
		var missingGeneration = GuardianTeamReplyReview.inspect(completeReply().replace("신청 차수: 1\n", ""), current);
		assertThat(missingGeneration.missingFields()).containsExactly("신청 차수");
		var currentReply = GuardianTeamReplyReview.inspect(completeReply().replace("신청 차수: 1\n", "신청 차수: 2\n"), current);
		assertThat(currentReply.missingFields()).isEmpty();
		assertThat(currentReply.manualChecks()).noneSatisfy(check -> assertThat(check).contains("신청 차수: 현재 신청 기록과 일치하지 않습니다"));
		assertThat(currentReply.requiresHumanReview()).isTrue();
	}

	@Test
	void refusalIsNeverTreatedAsConsentAndOmittedOptionalConsentIsNotInvented() {
		String reply = completeReply().replace("필수 동의(SERVICE): 동의합니다", "필수 동의(SERVICE): 동의하지 않습니다")
			.replace("\n선택 동의(EXTERNAL_AI): 동의하지 않습니다", "");
		var result = GuardianTeamReplyReview.inspect(reply, GuardianTeamFormsTest.context());
		assertThat(result.missingFields()).isEmpty();
		assertThat(result.manualChecks()).anySatisfy(check -> assertThat(check).contains("거절 또는 불명확한 회신을 승인 동의로 처리하지 마세요"))
			.anySatisfy(check -> assertThat(check).contains("선택 동의가 있었다고 기록하지 마세요"));
	}

	@Test
	void untrustedMarkupRemainsPlainDataAndCannotCreateAnApprovalResult() {
		String reply = completeReply().replace("합성 보호자", "<script>alert('untrusted')</script>");
		var result = GuardianTeamReplyReview.inspect(reply, GuardianTeamFormsTest.context());
		assertThat(result.suppliedFields().get("법정대리인 성명")).isEqualTo("<script>alert('untrusted')</script>");
		assertThat(result.requiresHumanReview()).isTrue();
		assertThat(result.toString()).doesNotContain("<script>", "untrusted");
	}

	@Test
	void excessiveTextAndHiddenControlsFailWithoutReturningTheBody() {
		assertThatThrownBy(() -> GuardianTeamReplyReview.inspect("private".repeat(2000), GuardianTeamFormsTest.context()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
		assertThatThrownBy(() -> GuardianTeamReplyReview.inspect("성명: private\u0000value", GuardianTeamFormsTest.context()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
		assertThatThrownBy(() -> GuardianTeamReplyReview.inspect("성명: private\u202evalue", GuardianTeamFormsTest.context()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
	}

	@Test
	void fieldsAndChecklistsCannotBeMutatedAfterInspection() {
		var result = GuardianTeamReplyReview.inspect(completeReply(), GuardianTeamFormsTest.context());
		assertThatThrownBy(() -> result.suppliedFields().put("관계", "other")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.missingFields().add("extra")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.manualChecks().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private String completeReply() {
		var context = GuardianTeamFormsTest.context();
		return "신청번호: " + context.requestId() + "\n"
			+ "신청 차수: " + context.generation() + "\n"
			+ "법정대리인 성명: 합성 보호자\n"
			+ "관계: 부모\n"
			+ "법정대리인 자기 확인: 위 아동의 법정대리인임을 확인합니다\n"
			+ "동의문 버전: " + context.noticeVersion() + "\n"
			+ "동의문 SHA-256: " + context.noticeDigest() + "\n"
			+ "필수 동의(SERVICE): 동의합니다\n"
			+ "선택 동의(EXTERNAL_AI): 동의하지 않습니다";
	}
}
