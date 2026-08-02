package io.edupilot.session;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class SessionWithdrawalHook implements UserWithdrawalHook {

	private final LearningSessionRepository sessionRepository;
	private final Clock clock;

	public SessionWithdrawalHook(
		LearningSessionRepository sessionRepository,
		Clock clock
	) {
		this.sessionRepository = sessionRepository;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void onWithdraw(Long userId) {
		sessionRepository.deleteAllByUserId(userId, clock.instant());
	}
}
