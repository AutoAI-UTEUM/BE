package io.edupilot.guardian;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.global.error.*;
import io.edupilot.user.*;

/** Legacy access is a policy exception; new-account eligibility still requires an approved age/guardian policy. */
@Service
public class AgeEligibilityGate {
 private final UserRepository users;
 public AgeEligibilityGate(UserRepository users){this.users=users;}
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
 public void requireEligible(Long userId) {
  User user=users.findById(userId).orElseThrow(()->new BusinessException(ErrorCode.USER_INACTIVE));
  if(user.getStatus()==UserStatus.SUSPENDED)throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
  if(!user.isActive())throw new BusinessException(ErrorCode.USER_INACTIVE);
  ErrorCode failure=UserBusinessAccessState.ageFailure(user.getAccessCohort(),user.getAgeVerificationState());
  if(failure!=null)throw new BusinessException(failure);
 }
}
