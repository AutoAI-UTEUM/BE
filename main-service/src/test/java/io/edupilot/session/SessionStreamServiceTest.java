package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class SessionStreamServiceTest {

	private final LearningSessionRepository repository =
		mock(LearningSessionRepository.class);
	private final SessionStreamService service =
		new SessionStreamService(repository);

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	@Test
	void validatesOwnershipAndActiveStatus() {
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.empty());
		assertError(
			() -> service.connect(1L, 100L),
			ErrorCode.SESSION_NOT_FOUND
		);

		LearningSession completed = mock(LearningSession.class);
		when(completed.getStatus()).thenReturn(SessionStatus.COMPLETED);
		when(repository.findByIdAndUser_Id(101L, 1L))
			.thenReturn(Optional.of(completed));
		assertError(
			() -> service.connect(1L, 101L),
			ErrorCode.SESSION_NOT_ACTIVE
		);
	}

	@Test
	void replacesIdleConnectionButRejectsConcurrentRunningConnection() {
		LearningSession active = mock(LearningSession.class);
		when(active.getStatus()).thenReturn(SessionStatus.ACTIVE);
		when(repository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(active));

		var first = service.connect(1L, 100L);
		var second = service.connect(1L, 100L);
		assertThat(second).isNotSameAs(first);

		AiStreamCancellation cancellation = new AiStreamCancellation();
		assertThat(service.beginTurn(1L, 100L, cancellation)).isPresent();
		assertError(
			() -> service.connect(1L, 100L),
			ErrorCode.TURN_IN_PROGRESS
		);
	}

	private void assertError(Runnable action, ErrorCode code) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(code)
			);
	}
}
