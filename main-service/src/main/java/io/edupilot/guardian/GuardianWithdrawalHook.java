package io.edupilot.guardian;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.user.UserWithdrawalHook;

@Component
public class GuardianWithdrawalHook implements UserWithdrawalHook {
 private final GuardianVerificationRequestRepository requests;
 public GuardianWithdrawalHook(GuardianVerificationRequestRepository requests){this.requests=requests;}
 @Override @Transactional(propagation=Propagation.MANDATORY)
 public void onWithdraw(Long userId){requests.cancelPending(userId,GuardianVerificationRequest.Status.PENDING,GuardianVerificationRequest.Status.CANCELLED);}
}
