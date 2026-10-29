package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import io.edupilot.ai.AiClient;
import io.edupilot.aiusage.AiQuotaProperties;
import io.edupilot.aiusage.AiQuotaService;
import io.edupilot.aiusage.AiUsageLogRepository;
import io.edupilot.aiusage.AiUsageService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.*;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.*;

class EmailVerificationGateTest {
	@Test void pendingAccountCannotReachFileLoadingOrAiThroughABackgroundWorker() {
		UserRepository users=mock(UserRepository.class);
		when(users.findById(1L)).thenReturn(Optional.of(User.create("synthetic@example.com","hash","Synthetic",UserRole.LEARNER)));
		EmailVerificationGate gate=new EmailVerificationGate(users,mock(LearningMaterialRepository.class),mock(LearningSessionRepository.class), Clock.systemUTC());
		var persistence=mock(MaterialExtractionPersistenceService.class);
		when(persistence.snapshot(10L)).thenReturn(Optional.of(new MaterialExtractionPersistenceService.ExtractionSnapshot(10L,1L,"synthetic/key.pdf")));
		FileStorage storage=mock(FileStorage.class); AiClient ai=mock(AiClient.class);
		new MaterialExtractionService(persistence,storage,ai,mock(AiUsageService.class),
			new MaterialProperties(45,300,java.time.Duration.ofMinutes(30)),mock(MaterialOutlineTaskDispatcher.class),
			mock(MaterialCaptionTaskDispatcher.class),mock(MaterialXaiFileLifecycleService.class),gate, io.edupilot.GuardianConsentFenceTestSupport.legacy()).extract(10L,"synthetic-trace");
		verifyNoInteractions(storage,ai);
	}
	@Test void quotaDisabledAndAdminRoleCannotBypassEmailOwnership() {
		for (UserRole role : new UserRole[]{UserRole.LEARNER,UserRole.ADMIN}) {
			UserRepository users=mock(UserRepository.class);
			when(users.findById(1L)).thenReturn(Optional.of(User.create("synthetic@example.com","hash","Synthetic",role)));
			var gate=new EmailVerificationGate(users,mock(LearningMaterialRepository.class),mock(LearningSessionRepository.class), Clock.systemUTC());
			var usage=mock(AiUsageLogRepository.class);
			var quota=new AiQuotaService(usage,new AiQuotaProperties(false,200,500),Clock.systemUTC(),gate);
			assertThatThrownBy(()->quota.checkQuota(1L,role)).isInstanceOfSatisfying(BusinessException.class,
				error->org.assertj.core.api.Assertions.assertThat(error.errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED));
			verifyNoInteractions(usage);
		}
	}
	@Test void verifiedFlagWithoutTimestampDoesNotGrantOwnership() {
		User user=User.create("synthetic@example.com","hash","Synthetic",UserRole.LEARNER);
		org.springframework.test.util.ReflectionTestUtils.setField(user,"emailVerificationState",EmailVerificationState.VERIFIED);
		UserRepository users=mock(UserRepository.class); when(users.findById(1L)).thenReturn(Optional.of(user));
		var gate=new EmailVerificationGate(users,mock(LearningMaterialRepository.class),mock(LearningSessionRepository.class), Clock.systemUTC());
		assertThatThrownBy(()->gate.requireVerified(1L)).isInstanceOf(BusinessException.class);
	}
}
