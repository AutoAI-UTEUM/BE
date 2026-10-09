package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

/** Policy options are not declarations, approvals, or policy activation. */
class GuardianTeamScopeOptionsTest {
	private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");

	@ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"\t", "\u3000"})
	void anAbsentOptionalAiScopeIsNullWhileTheCurrentServiceScopeRemains(String optional) {
		var policy = policy(optional, true);
		assertThat(policy.ready()).isTrue();
		var detail = detail(policy, policy);
		assertThat(detail.status().currentNotice()).isTrue(); assertThat(detail.requiredScopes()).containsExactly("SERVICE");
		assertThat(detail.optionalAiScope()).isNull(); assertThat(detail.declaredScopes()).isEmpty();
		assertThat(detail.status().serviceApproved()).isFalse(); assertThat(detail.status().externalAiApproved()).isFalse();
	}

	@Test void changingOnlyTheOptionalAiPolicyCannotAttachNewOptionsToAnOldGeneration() {
		var original = policy(null, true); var current = policy("EXTERNAL_AI", true);
		assertThat(original.noticeVersion()).isEqualTo(current.noticeVersion());
		assertThat(original.noticeDigest()).isEqualTo(current.noticeDigest());
		assertThat(original.configurationDigest()).isNotEqualTo(current.configurationDigest());
		var detail = detail(current, original);
		assertThat(detail.status().generation()).isEqualTo(1); assertThat(detail.status().revision()).isEqualTo(1);
		assertThat(detail.status().noticeVersion()).isEqualTo(original.noticeVersion());
		assertThat(detail.status().noticeDigest()).isEqualTo(original.noticeDigest());
		assertThat(detail.status().currentNotice()).isFalse(); assertThat(detail.requiredScopes()).isEmpty();
		assertThat(detail.optionalAiScope()).isNull(); assertThat(detail.replyChannel()).isNull(); assertThat(detail.forms()).isEmpty();
		assertThat(detail.status().serviceApproved()).isFalse(); assertThat(detail.status().externalAiApproved()).isFalse();
	}

	@Test void unavailablePolicyCannotReturnReviewerScopeOptionsOrReadCaseData() {
		var policy = policy("EXTERNAL_AI", false); var users = mock(UserRepository.class);
		var requests = mock(GuardianTeamRequestRepository.class); var events = mock(GuardianTeamEventRepository.class);
		var service = service(policy, users, requests, events);
		assertThatThrownBy(() -> service.detail(1L, "not-read"))
			.isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.GUARDIAN_TEAM_UNAVAILABLE));
		verifyNoInteractions(users, requests, events);
	}

	private static GuardianTeamDtos.Detail detail(GuardianTeamProperties current, GuardianTeamProperties requested) {
		var users = mock(UserRepository.class); var requests = mock(GuardianTeamRequestRepository.class);
		var events = mock(GuardianTeamEventRepository.class);
		var reviewer = User.create("reviewer@example.invalid", "unused", "synthetic reviewer", UserRole.ADMIN);
		var child = User.create("child@example.invalid", "unused", "synthetic child", UserRole.LEARNER);
		child.recordSignupDateOfBirth(LocalDate.of(2016, 1, 1));
		ReflectionTestUtils.setField(reviewer, "id", 1L); ReflectionTestUtils.setField(child, "id", 2L);
		var row = GuardianTeamRequest.create(child, requested, requested.configurationDigest(), NOW, null, null);
		when(requests.ownerOfId(row.id())).thenReturn(Optional.of(2L));
		when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(reviewer)); when(users.findByIdForUpdate(2L)).thenReturn(Optional.of(child));
		when(requests.findByIdForUpdate(row.id())).thenReturn(Optional.of(row));
		when(events.findByRequestIdOrderByRecordedAtAscIdAsc(row.id(), PageRequest.of(0, 200))).thenReturn(List.of());
		return service(current, users, requests, events).detail(1L, row.id());
	}
	private static GuardianTeamService service(GuardianTeamProperties policy, UserRepository users,
		GuardianTeamRequestRepository requests, GuardianTeamEventRepository events) {
		return new GuardianTeamService(policy, users, requests, events, mock(GuardianTeamOperationRepository.class),
			new GuardianTeamSecrets(), mock(GuardianTeamMailCleanup.class), Clock.fixed(NOW, ZoneOffset.UTC));
	}
	private static GuardianTeamProperties policy(String optional, boolean confirmed) {
		var first = GuardianTeamPropertiesTest.settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), confirmed);
		return new GuardianTeamProperties(first.enabled(), first.policyConfirmed(), first.portalBaseUrl(), first.noticeVersion(),
			first.noticeDigest(), first.noticeUrl(), first.collectionItemsText(), first.purposesText(), first.retentionText(), first.refusalText(),
			first.replyContact(), first.replyChannel(), first.reviewerIds(), first.requiredScopes(), optional,
			optional == null || optional.isBlank() ? null : first.optionalConsentText(),
			first.linkTtl(), first.requestTtl(), first.approvedEvidenceRetention(), first.approvalValidity());
	}
}
