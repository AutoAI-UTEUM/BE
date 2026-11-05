package io.edupilot.guardian;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.user.UserWithdrawalHook;

@Component
public class GuardianWebWithdrawalHook implements UserWithdrawalHook {
	private final java.util.function.Consumer<Long> cancel;
	@Autowired
	public GuardianWebWithdrawalHook(GuardianWebPersistence persistence) { this.cancel = persistence::cancelUser; }
	private GuardianWebWithdrawalHook(java.util.function.Consumer<Long> cancel) { this.cancel = cancel; }
	public static GuardianWebWithdrawalHook forRecovery(GuardianWebCancellation cancellation) {
		return new GuardianWebWithdrawalHook(cancellation::cancelUser);
	}
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onWithdraw(Long userId) { cancel.accept(userId); }
}
