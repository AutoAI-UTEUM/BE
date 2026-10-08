package io.edupilot.quiz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.ai.AiClient;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.auth.EmailVerificationGate;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

class QuizGuardianConsentEpochTest {
	@Test
	void gradingUsesTheSubmittingWorkflowEpochWithoutRecapturingAfterReapproval() {
		Instant now = Instant.parse("2026-10-06T01:00:00Z");
		String digest = "a".repeat(64);
		User child = User.create("synthetic-child@example.invalid", "synthetic-hash", "합성 아동", UserRole.LEARNER);
		child.recordSignupDateOfBirth(LocalDate.of(2013, 1, 1));
		child.verifyEmail(now.minusSeconds(60));
		ReflectionTestUtils.setField(child, "id", 1L);
		child.recordGuardianTeamApproval(now.plusSeconds(600), true);
		child.recordGuardianTeamPolicyDigest(digest);
		UserRepository users = mock(UserRepository.class);
		when(users.findBusinessAccessStateById(1L)).thenAnswer(call -> Optional.of(UserBusinessAccessState.from(child)));
		GuardianTeamProperties policy = mock(GuardianTeamProperties.class);
		when(policy.ready()).thenReturn(true);
		when(policy.configurationDigest()).thenReturn(digest);
		GuardianConsentFence fence = new GuardianConsentFence(users, mock(LearningMaterialRepository.class),
			mock(LearningSessionRepository.class), Clock.fixed(now, ZoneOffset.UTC), policy);
		GuardianConsentFence.Snapshot original = fence.capture(1L);
		child.clearGuardianTeamApproval(false);
		child.recordGuardianTeamApproval(now.plusSeconds(600), true);
		child.recordGuardianTeamPolicyDigest(digest);
		AiClient ai = mock(AiClient.class);
		AiUsageService usage = mock(AiUsageService.class);
		QuizGradingService service = new QuizGradingService(ai, usage, new DeterministicAnswerGrader(),
			mock(EmailVerificationGate.class), fence);
		PreparedQuizSubmission prepared = mock(PreparedQuizSubmission.class);
		when(prepared.quizType()).thenReturn(QuizType.SHORT);
		assertThatThrownBy(() -> service.grade(1L, prepared, original)).isInstanceOfSatisfying(BusinessException.class,
			error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.GUARDIAN_CONSENT_CHANGED));
		verifyNoInteractions(ai, usage);
	}
}
