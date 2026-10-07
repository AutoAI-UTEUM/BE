package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

class GuardianTeamEntryTest {
	@Test void unreadyPolicyReturnsATargetSignalWithoutOpeningOrReadingTheCaseWorkflow() {
		var policy = mock(GuardianTeamProperties.class);
		var users = mock(UserRepository.class);
		var requests = mock(GuardianTeamRequestRepository.class);
		var events = mock(GuardianTeamEventRepository.class);
		var operations = mock(GuardianTeamOperationRepository.class);
		var secrets = mock(GuardianTeamSecrets.class);
		var cleanup = mock(GuardianTeamMailCleanup.class);
		User child = User.create("entry@example.invalid", "!synthetic", "합성 계정");
		child.recordSignupDateOfBirth(LocalDate.of(2016, 1, 1));
		when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(child));
		var service = new GuardianTeamService(policy, users, requests, events, operations, secrets, cleanup,
			Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));

		var result = service.entry(7L);

		assertThat(result.requirement()).isEqualTo(GuardianTeamDtos.Requirement.REQUIRED);
		assertThat(result.teamReviewAvailable()).isFalse(); assertThat(result.canStartRequest()).isFalse();
		assertThat(result.replyChannel()).isNull(); assertThat(result.request()).isNull();
		verifyNoInteractions(requests, events, operations, secrets, cleanup);
	}
}
