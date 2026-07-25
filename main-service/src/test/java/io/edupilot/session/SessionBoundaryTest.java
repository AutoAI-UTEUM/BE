package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class SessionBoundaryTest {

	@Mock
	private LearningSessionRepository sessionRepository;

	@Test
	void activeSessionBlocksMaterialDeletionAndWithdrawalDeletesAll() {
		SessionMaterialDeletionGuard guard = new SessionMaterialDeletionGuard(
			sessionRepository
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

		new SessionWithdrawalHook(sessionRepository).onWithdraw(1L);
		verify(sessionRepository).deleteAllByUserId(1L);
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
