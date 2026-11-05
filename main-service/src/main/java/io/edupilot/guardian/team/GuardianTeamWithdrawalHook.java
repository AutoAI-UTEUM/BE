package io.edupilot.guardian.team;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.user.UserWithdrawalHook;

@Component
public class GuardianTeamWithdrawalHook implements UserWithdrawalHook {
	private final java.util.function.Consumer<Long> cancel;
	@Autowired
	public GuardianTeamWithdrawalHook(GuardianTeamService service) { this.cancel = service::cancelOnWithdrawal; }
	private GuardianTeamWithdrawalHook(java.util.function.Consumer<Long> cancel) { this.cancel = cancel; }
	public static GuardianTeamWithdrawalHook forRecovery(GuardianTeamWithdrawalCleanup cleanup) {
		return new GuardianTeamWithdrawalHook(cleanup::cancelOnWithdrawal);
	}
	@Override @Transactional(propagation = Propagation.MANDATORY)
	public void onWithdraw(Long userId) { cancel.accept(userId); }
}
