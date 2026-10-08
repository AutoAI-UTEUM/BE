package io.edupilot.user.birthdate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class BirthdateCorrectionWithdrawalHook implements UserWithdrawalHook {
	private final BirthdateCorrectionRepository requests;
	public BirthdateCorrectionWithdrawalHook(BirthdateCorrectionRepository requests) { this.requests = requests; }

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onWithdraw(Long userId) {
		// The withdrawal transaction already holds and flushes the current User row before invoking hooks.
		requests.eraseOnWithdrawal(userId, BirthdateCorrectionRequest.State.WITHDRAWN);
	}
}
