package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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
class TurnClaimServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private ChatMessageRepository messageRepository;

	private TurnClaimService claimService;
	private LearningSession session;

	@BeforeEach
	void setUp() {
		claimService = new TurnClaimService(
			sessionRepository,
			messageRepository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		User user = User.create("user@example.com", "hash", "사용자");
		ReflectionTestUtils.setField(user, "id", 1L);
		LearningMaterial material = LearningMaterial.create(
			user,
			"자료",
			"materials/test.pdf"
		);
		ReflectionTestUtils.setField(material, "id", 10L);
		session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 100L);
	}

	@Test
	void claimsWithFiveMinuteStaleCutoffAndReleasesMatchingRequest() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(sessionRepository.claimTurn(
			100L,
			1L,
			"request-1",
			NOW,
			NOW,
			NOW.minusSeconds(300)
		)).thenReturn(1);

		claimService.claim(1L, 100L, "request-1");
		claimService.release(100L, "request-1");

		verify(sessionRepository).claimTurn(
			100L,
			1L,
			"request-1",
			NOW,
			NOW,
			NOW.minusSeconds(300)
		);
		verify(sessionRepository).releaseTurn(100L, "request-1", NOW);
	}

	@Test
	void rejectsProcessedAndConcurrentTurnsWithStableErrors() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(messageRepository.existsBySession_IdAndRequestId(
			100L,
			"duplicate"
		)).thenReturn(true);

		assertError(
			() -> claimService.claim(1L, 100L, "duplicate"),
			ErrorCode.TURN_ALREADY_PROCESSED
		);

		when(messageRepository.existsBySession_IdAndRequestId(
			100L,
			"request-2"
		)).thenReturn(false);
		when(sessionRepository.claimTurn(
			100L,
			1L,
			"request-2",
			NOW,
			NOW,
			NOW.minusSeconds(300)
		)).thenReturn(0);
		assertError(
			() -> claimService.claim(1L, 100L, "request-2"),
			ErrorCode.TURN_IN_PROGRESS
		);
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
