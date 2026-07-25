package io.edupilot.session;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class SessionWithdrawalHook implements UserWithdrawalHook {

	private final LearningSessionRepository sessionRepository;

	public SessionWithdrawalHook(LearningSessionRepository sessionRepository) {
		this.sessionRepository = sessionRepository;
	}

	@Override
	@Transactional
	public void onWithdraw(Long userId) {
		sessionRepository.deleteAllByUserId(userId);
	}
}
