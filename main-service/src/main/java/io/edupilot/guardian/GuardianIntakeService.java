package io.edupilot.guardian;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.global.error.*;
import io.edupilot.user.*;

/** Internal intake service. No approver authority, evidence upload or public approval endpoint is invented. */
@Service
public class GuardianIntakeService {
 private final UserRepository users;
 private final GuardianIntakeProvider provider;
 public GuardianIntakeService(UserRepository users,GuardianIntakeProvider provider){this.users=users;this.provider=provider;}
 @Transactional
 public Pending request(Long userId) {
  User user=users.findByIdForUpdate(userId).orElseThrow(()->new BusinessException(ErrorCode.USER_NOT_FOUND));
  if(!user.isActive())throw new BusinessException(ErrorCode.USER_INACTIVE);
  GuardianVerificationRequest request=provider.request(user);
  if(!userId.equals(request.getUserId())||request.getStatus()!=GuardianVerificationRequest.Status.PENDING)
   throw new BusinessException(ErrorCode.ACCESS_DENIED);
  user.beginGuardianVerification();
  return new Pending(request.getId(),request.getProvider(),request.getStatus());
 }
 public record Pending(String requestId,String provider,GuardianVerificationRequest.Status status) {}
}
