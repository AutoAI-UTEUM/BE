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
			null, null, null, null, null, null, null, null, null, null);
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
			first.linkTtl(), first.requestTtl(), first.approvedEvidenceRetention(), first.approvalValidity());
		assertThat(first.configurationDigest()).matches("[a-f0-9]{64}").isNotEqualTo(changed.configurationDigest());
	}
	@Test void unconfirmedRequestsHaveAnExplicitMaximumFiveDayWindow() {
		var first = settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
		var changed = new GuardianTeamProperties(first.enabled(), first.policyConfirmed(), first.portalBaseUrl(), first.noticeVersion(),
			first.noticeDigest(), first.noticeUrl(), first.collectionItemsText(), first.purposesText(), first.retentionText(), first.refusalText(),
			first.replyContact(), first.replyChannel(), first.reviewerIds(), first.requiredScopes(), first.optionalAiScope(), first.optionalConsentText(),
			first.linkTtl(), Duration.ofDays(6), first.approvedEvidenceRetention(), first.approvalValidity());
		assertThat(changed.ready()).isFalse();
	}
	@Test void approvalCannotRemainValidAfterItsEvidenceMaximumRetention() {
		assertThat(settings(Duration.ofDays(1), Duration.ofDays(7), List.of("SERVICE"), true).ready()).isFalse();
		assertThat(settings(Duration.ofDays(7), Duration.ofDays(7), List.of("SERVICE"), true).ready()).isTrue();
	}
	static GuardianTeamProperties settings(Duration retention, Duration validity, List<String> scopes, boolean confirmed) {
		return new GuardianTeamProperties(true, confirmed, "https://guardian.example.invalid", "synthetic-v1", "a".repeat(64),
			"https://guardian.example.invalid/notice", "보호자 연락처와 확인 결과", "팀의 보호자 동의 확인", "확인된 합성 테스트 보유 안내",
			"동의를 거부하면 보호자 확인이 필요한 서비스를 이용할 수 없습니다.", "reply@example.invalid", "EMAIL_REPLY", List.of(1L),
			scopes, "EXTERNAL_AI", "외부 AI 전송 여부를 선택합니다.", Duration.ofMinutes(30), Duration.ofDays(5), retention, validity);
	}
}
