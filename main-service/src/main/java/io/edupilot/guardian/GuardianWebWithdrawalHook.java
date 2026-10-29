package io.edupilot.guardian;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.user.UserWithdrawalHook;

@Component
public class GuardianWebWithdrawalHook implements UserWithdrawalHook {
	private final GuardianWebPersistence persistence;
	public GuardianWebWithdrawalHook(GuardianWebPersistence persistence) { this.persistence = persistence; }
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onWithdraw(Long userId) { persistence.cancelUser(userId); }
}
