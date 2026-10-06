package io.edupilot;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.util.HashMap;
import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.guardian.GuardianConsentFence;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import jakarta.persistence.EntityManager;

/** 다른 기능의 단위 테스트에서 사용하는 합성 LEGACY_EXEMPT 계정과 실제 동의 경계입니다. */
public final class GuardianConsentFenceTestSupport {
	private GuardianConsentFenceTestSupport() { }

	public static GuardianConsentFence legacy() {
		UserRepository users = mock(UserRepository.class);
		var actors = new HashMap<Long, User>();
		java.util.function.Function<Long, User> actor = id -> actors.computeIfAbsent(id, key -> {
			User user = VerifiedTestUsers.legacyVerified(User.create("legacy" + key + "@example.invalid",
				"synthetic-hash", "합성 계정", UserRole.LEARNER));
			ReflectionTestUtils.setField(user, "id", key);
			return user;
		});
		lenient().when(users.findBusinessAccessStateById(anyLong()))
			.thenAnswer(call -> Optional.of(UserBusinessAccessState.from(actor.apply(call.getArgument(0)))));
		lenient().when(users.findByIdForBusinessAccess(anyLong()))
			.thenAnswer(call -> Optional.of(actor.apply(call.getArgument(0))));
		LearningMaterialRepository materials = mock(LearningMaterialRepository.class);
		LearningMaterial material = mock(LearningMaterial.class);
		lenient().when(material.getOwnerId()).thenReturn(1L);
		lenient().when(materials.findById(anyLong())).thenReturn(Optional.of(material));
		LearningSessionRepository sessions = mock(LearningSessionRepository.class);
		LearningSession session = mock(LearningSession.class);
		lenient().when(session.getUserId()).thenReturn(1L);
		lenient().when(sessions.findById(anyLong())).thenReturn(Optional.of(session));
		GuardianConsentFence fence = new GuardianConsentFence(users, materials, sessions, Clock.systemUTC(),
			mock(GuardianTeamProperties.class));
		ReflectionTestUtils.setField(fence, "entityManager", mock(EntityManager.class));
		return fence;
	}
}
