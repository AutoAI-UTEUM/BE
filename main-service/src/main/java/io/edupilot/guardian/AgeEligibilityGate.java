package io.edupilot.guardian;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.global.error.*;
import io.edupilot.user.*;

/** KST year eligibility and legacy access exceptions do not certify guardian evidence. */
@Service
public class AgeEligibilityGate {
 private final UserRepository users;
 private final Clock clock;
 public AgeEligibilityGate(UserRepository users,Clock clock){this.users=users;this.clock=clock;}
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
 public void requireEligible(Long userId) {
  User user=users.findById(userId).orElseThrow(()->new BusinessException(ErrorCode.USER_INACTIVE));
  if(user.getStatus()==UserStatus.SUSPENDED)throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
  if(!user.isActive())throw new BusinessException(ErrorCode.USER_INACTIVE);
  ErrorCode failure=UserBusinessAccessState.ageFailure(user.getAccessCohort(),user.getAgeVerificationState(),user.getDateOfBirth(),clock);
  if(failure!=null)throw new BusinessException(failure);
 }
}
