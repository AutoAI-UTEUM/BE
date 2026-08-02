package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class SessionBoundaryTest {
	private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

	@Mock
	private LearningSessionRepository sessionRepository;
	@Mock
	private ClassroomWeekMaterialRepository weekMaterialRepository;

	@Test
	void activeSessionBlocksMaterialDeletionAndWithdrawalDeletesAll() {
		SessionMaterialDeletionGuard guard = new SessionMaterialDeletionGuard(
			sessionRepository,
			weekMaterialRepository
		);
		when(sessionRepository.existsByMaterial_IdAndStatus(
			10L,
			SessionStatus.ACTIVE
		)).thenReturn(true);

		assertThatThrownBy(() -> guard.assertDeletable(10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_HAS_ACTIVE_SESSION)
			);

		new SessionWithdrawalHook(
			sessionRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		).onWithdraw(1L);
		verify(sessionRepository).deleteAllByUserId(1L, NOW);
	}

	@Test
	void classroomLinkBlocksMaterialDeletion() {
		SessionMaterialDeletionGuard guard = new SessionMaterialDeletionGuard(
			sessionRepository,
			weekMaterialRepository
		);
		when(weekMaterialRepository.existsByMaterial_Id(10L)).thenReturn(true);

		assertThatThrownBy(() -> guard.assertDeletable(10L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.MATERIAL_LINKED_TO_CLASSROOM)
			);
	}

	@Test
	void onlyUserMessageCarriesRequestId() {
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/test.pdf"
		);
		LearningSession session = LearningSession.create(user, material);

		ChatMessage userMessage = ChatMessage.user(session, "질문", "request-1");
		ChatMessage aiMessage1 = ChatMessage.ai(session, "응답1");
		ChatMessage aiMessage2 = ChatMessage.ai(session, "응답2");

		assertThat(userMessage.getRequestId()).isEqualTo("request-1");
		assertThat(aiMessage1.getRequestId()).isNull();
		assertThat(aiMessage2.getRequestId()).isNull();
	}
}
