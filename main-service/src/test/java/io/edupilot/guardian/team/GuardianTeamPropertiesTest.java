package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;
import io.edupilot.user.UserRepository;

class GuardianTeamPropertiesTest {
	@Test void disabledAndUnconfirmedSettingsNeverAcceptRequests() {
		var disabled = new GuardianTeamProperties(false, false, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null);
		assertThat(disabled.ready()).isFalse(); disabled.validateActivation();
		var users = mock(UserRepository.class);
		var service = new GuardianTeamService(disabled, users, mock(GuardianTeamRequestRepository.class),
			mock(GuardianTeamEventRepository.class), mock(GuardianTeamOperationRepository.class), new GuardianTeamSecrets(),
			mock(GuardianTeamMailCleanup.class), Clock.systemUTC());
		assertThatThrownBy(() -> service.intake(1L, new GuardianTeamDtos.Intake("new", null, null, false)))
			.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GUARDIAN_TEAM_UNAVAILABLE));
		verifyNoInteractions(users);
	}
	@Test void activationRequiresExplicitApprovedRetentionAndValidity() {
		assertThatThrownBy(() -> settings(null, Duration.ofDays(7), List.of("SERVICE"), true).validateActivation())
			.isInstanceOf(IllegalStateException.class);
		assertThat(settings(Duration.ofDays(30), null, List.of("SERVICE"), true).ready()).isFalse();
		assertThat(settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), false).ready()).isFalse();
	}
	@Test void requiredExternalAiConsentCannotBeForcedIntoServiceConsent() {
		assertThat(settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE", "EXTERNAL_AI"), true).ready()).isFalse();
		assertThat(settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true).ready()).isTrue();
	}
	@Test void aPolicySnapshotChangesWhenPurposeOrOptionalConsentChanges() {
		var first = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		var changed = new GuardianTeamProperties(first.enabled(), first.policyConfirmed(), first.portalBaseUrl(), first.noticeVersion(),
			first.noticeDigest(), first.noticeUrl(), first.collectionItemsText(), "변경된 확인 목적", first.retentionText(), first.refusalText(),
			first.replyContact(), first.replyChannel(), first.reviewerIds(), first.requiredScopes(), first.optionalAiScope(), first.optionalConsentText(),
			first.linkTtl(), first.requestTtl(), first.approvedEvidenceRetention(), first.approvalValidity(), first.priorConsent());
		assertThat(first.configurationDigest()).matches("[a-f0-9]{64}").isNotEqualTo(changed.configurationDigest());
	}
	@Test void unconfirmedRequestsHaveAnExplicitMaximumFiveDayWindow() {
		var first = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		var changed = new GuardianTeamProperties(first.enabled(), first.policyConfirmed(), first.portalBaseUrl(), first.noticeVersion(),
			first.noticeDigest(), first.noticeUrl(), first.collectionItemsText(), first.purposesText(), first.retentionText(), first.refusalText(),
			first.replyContact(), first.replyChannel(), first.reviewerIds(), first.requiredScopes(), first.optionalAiScope(), first.optionalConsentText(),
			first.linkTtl(), Duration.ofDays(6), first.approvedEvidenceRetention(), first.approvalValidity(), first.priorConsent());
		assertThat(changed.ready()).isFalse();
	}
	@Test void approvalCannotRemainValidAfterItsEvidenceMaximumRetention() {
		assertThat(settings(Duration.ofDays(1), Duration.ofDays(7), List.of("SERVICE"), true).ready()).isFalse();
		assertThat(settings(Duration.ofDays(7), Duration.ofDays(7), List.of("SERVICE"), true).ready()).isTrue();
	}
	@Test void storedRecordsDoNotEnableTheAdditionalPathOrChooseAMethodOrAgeLimit() {
		var original = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		assertThat(original.priorConsent().enabled()).isFalse(); assertThat(original.priorConsent().ready()).isFalse();
		var users = mock(UserRepository.class);
		var service = new GuardianTeamService(original, users, mock(GuardianTeamRequestRepository.class), mock(GuardianTeamEventRepository.class),
			mock(GuardianTeamOperationRepository.class), new GuardianTeamSecrets(), mock(GuardianTeamMailCleanup.class), Clock.systemUTC());
		assertThatThrownBy(() -> service.approvePriorConsent(1L, 2L, null)).isInstanceOfSatisfying(BusinessException.class,
			e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GUARDIAN_TEAM_UNAVAILABLE));
		verifyNoInteractions(users);
		for (var invalid : List.of(new GuardianPriorConsentPolicy(true, false, List.of(GuardianPriorConsentPolicy.Method.SIGNED_DOCUMENT), "synthetic", Duration.ofDays(30)),
			new GuardianPriorConsentPolicy(true, true, List.of(), "synthetic", Duration.ofDays(30)),
			new GuardianPriorConsentPolicy(true, true, List.of(GuardianPriorConsentPolicy.Method.SIGNED_DOCUMENT), null, Duration.ofDays(30)),
			new GuardianPriorConsentPolicy(true, true, List.of(GuardianPriorConsentPolicy.Method.SIGNED_DOCUMENT), "synthetic", null))) {
			assertThat(withPrior(original, invalid).ready()).isFalse();
			assertThatThrownBy(() -> withPrior(original, invalid).validateActivation()).isInstanceOf(IllegalStateException.class);
		}
	}
	@Test void reviewedPriorConsentPolicyChangesInvalidateTheSameApprovalSnapshotAsOtherNoticeChanges() {
		var original = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		var first = withPrior(original, new GuardianPriorConsentPolicy(true, true, List.of(GuardianPriorConsentPolicy.Method.SIGNED_DOCUMENT), "reviewed-policy-1", Duration.ofDays(30)));
		var changed = withPrior(original, new GuardianPriorConsentPolicy(true, true, List.of(GuardianPriorConsentPolicy.Method.SIGNED_DOCUMENT), "reviewed-policy-2", Duration.ofDays(30)));
		assertThat(first.ready()).isTrue(); assertThat(first.configurationDigest()).isNotEqualTo(original.configurationDigest()).isNotEqualTo(changed.configurationDigest());
		assertThat(withPrior(original, new GuardianPriorConsentPolicy(false, false, List.of(), null, null)).configurationDigest()).isEqualTo(original.configurationDigest());
	}
	@Test void addingAnInPersonTypePreservesExistingDisabledAndUnselectedPolicyDigests() {
		var original = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		// Snapshots captured before IN_PERSON was added; merely adding an enum must not invalidate existing approvals.
		assertThat(original.configurationDigest()).isEqualTo("9313b4cbe71227aaa626224d1036c49f82b63d21bde71ab6a67622efd5a21176");
		var unchangedSigned = withPrior(original, new GuardianPriorConsentPolicy(true, true,
			List.of(GuardianPriorConsentPolicy.Method.SIGNED_DOCUMENT), "reviewed-policy-1", Duration.ofDays(30)));
		assertThat(unchangedSigned.configurationDigest()).isEqualTo("a57de91fd333b6ab52d19de3f9632cf3fce801b4abdc898a6a15fa0c2fb98b23");
		var selectedInPerson = withPrior(original, new GuardianPriorConsentPolicy(true, true,
			List.of(GuardianPriorConsentPolicy.Method.IN_PERSON), "reviewed-policy-1", Duration.ofDays(30)));
		assertThat(selectedInPerson.ready()).isTrue();
		assertThat(selectedInPerson.configurationDigest()).isNotEqualTo(original.configurationDigest()).isNotEqualTo(unchangedSigned.configurationDigest());
	}
	@Test void changingToAnInPersonPolicyRequiresRereviewOfExistingTeamApprovals() {
		var original = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		var inPerson = withPrior(original, new GuardianPriorConsentPolicy(true, true,
			List.of(GuardianPriorConsentPolicy.Method.IN_PERSON), "synthetic-reviewed-in-person", Duration.ofDays(30)));
		var now = java.time.Instant.parse("2026-10-29T00:00:00Z");
		var clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
		var child = io.edupilot.user.User.create("synthetic-digest@example.invalid", "!synthetic", "Synthetic child");
		child.recordSignupDateOfBirth(java.time.LocalDate.of(2016, 1, 1)); child.verifyEmail(now);
		child.recordGuardianTeamApproval(now.plus(Duration.ofDays(1)), false); child.recordGuardianTeamPolicyDigest(original.configurationDigest());
		var state = io.edupilot.user.UserBusinessAccessState.from(child);
		assertThat(state.eligibilityFailure(clock, true, original.configurationDigest())).isNull();
		assertThat(state.eligibilityFailure(clock, true, inPerson.configurationDigest())).isEqualTo(ErrorCode.AGE_VERIFICATION_REQUIRED);
		assertThat(child.getGuardianApprovalPolicyDigest()).isEqualTo(original.configurationDigest());
	}
	private static GuardianTeamProperties withPrior(GuardianTeamProperties first, GuardianPriorConsentPolicy prior) {
		return new GuardianTeamProperties(first.enabled(), first.policyConfirmed(), first.portalBaseUrl(), first.noticeVersion(),
			first.noticeDigest(), first.noticeUrl(), first.collectionItemsText(), first.purposesText(), first.retentionText(), first.refusalText(),
			first.replyContact(), first.replyChannel(), first.reviewerIds(), first.requiredScopes(), first.optionalAiScope(), first.optionalConsentText(),
			first.linkTtl(), first.requestTtl(), first.approvedEvidenceRetention(), first.approvalValidity(), prior);
	}
	static GuardianTeamProperties settings(Duration retention, Duration validity, List<String> scopes, boolean confirmed) {
		return new GuardianTeamProperties(true, confirmed, "https://guardian.example.invalid", "synthetic-v1", "a".repeat(64),
			"https://guardian.example.invalid/notice", "보호자 연락처와 확인 결과", "팀의 보호자 동의 확인", "확인된 합성 테스트 보유 안내",
			"동의를 거부하면 보호자 확인이 필요한 서비스를 이용할 수 없습니다.", "reply@example.invalid", "EMAIL_REPLY", List.of(1L),
			scopes, "EXTERNAL_AI", "외부 AI 전송 여부를 선택합니다.", Duration.ofMinutes(30), Duration.ofDays(5), retention, validity, null);
	}
}
