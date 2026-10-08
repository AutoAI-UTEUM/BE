package io.edupilot.guardian;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.user.User;

@Service
public class ManualGuardianIntakeProvider implements GuardianIntakeProvider {
 private final GuardianVerificationRequestRepository requests;
 private final Clock clock;
 public ManualGuardianIntakeProvider(GuardianVerificationRequestRepository requests,Clock clock){this.requests=requests;this.clock=clock;}
 @Override @Transactional(propagation=Propagation.MANDATORY)
 public GuardianVerificationRequest request(User user) {
  return requests.findByUser_Id(user.getId()).orElseGet(()->requests.saveAndFlush(GuardianVerificationRequest.manual(user,clock.instant())));
 }
}
