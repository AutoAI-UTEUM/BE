package io.edupilot.user;

@FunctionalInterface
public interface UserWithdrawalHook {

	void onWithdraw(Long userId);
}
