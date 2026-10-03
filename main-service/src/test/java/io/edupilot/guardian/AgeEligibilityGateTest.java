package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import io.edupilot.global.error.*;
import io.edupilot.user.*;

class AgeEligibilityGateTest {
 @Test void everyRoleWithUnknownAgeIsDeniedEvenWithDobOrVerifiedEmail() {
  for(UserRole role:UserRole.values()){
   var user=User.create("synthetic@example.com","hash","Synthetic",role);user.recordSignupDateOfBirth(LocalDate.of(2000,1,1));
   user.confirmEmailVerification(java.time.Instant.now());var users=mock(UserRepository.class);when(users.findById(1L)).thenReturn(Optional.of(user));
   assertError(new AgeEligibilityGate(users),ErrorCode.AGE_VERIFICATION_REQUIRED);
  }
 }
 @Test void recordedManualIntakeIsNotGuardianApproval() {
  var user=User.create("synthetic@example.com","hash","Synthetic");user.beginGuardianVerification();
  var users=mock(UserRepository.class);when(users.findById(1L)).thenReturn(Optional.of(user));assertError(new AgeEligibilityGate(users),ErrorCode.GUARDIAN_VERIFICATION_PENDING);
 }
 @Test void deletedAndSuspendedAccountsCannotBecomeEligible() {
  var users=mock(UserRepository.class);var user=User.create("synthetic@example.com","hash","Synthetic");user.suspend("Synthetic",1L,java.time.Instant.now());
  when(users.findById(1L)).thenReturn(Optional.of(user));assertError(new AgeEligibilityGate(users),ErrorCode.ACCOUNT_SUSPENDED);
  user.withdraw();assertError(new AgeEligibilityGate(users),ErrorCode.USER_INACTIVE);
 }
 void assertError(AgeEligibilityGate gate,ErrorCode error){assertThatThrownBy(()->gate.requireEligible(1L)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(error));}
}
