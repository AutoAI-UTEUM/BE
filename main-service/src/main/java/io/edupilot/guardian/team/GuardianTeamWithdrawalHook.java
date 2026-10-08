package io.edupilot.guardian.team;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.user.UserWithdrawalHook;

@Component
public class GuardianTeamWithdrawalHook implements UserWithdrawalHook {
	private final GuardianTeamService service;
	public GuardianTeamWithdrawalHook(GuardianTeamService service) { this.service = service; }
	@Override @Transactional(propagation = Propagation.MANDATORY)
	public void onWithdraw(Long userId) { service.cancelOnWithdrawal(userId); }
}
