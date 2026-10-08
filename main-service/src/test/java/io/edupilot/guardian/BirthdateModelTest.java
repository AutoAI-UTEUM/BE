package io.edupilot.guardian;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.edupilot.user.*;

class BirthdateModelTest {
 @Test void unsupportedSqlCalendarYearsAreRejectedWithoutInventingAgePolicy() {
  var user=User.create("synthetic@example.com","hash","Synthetic");
  for(int year:new int[]{0,10000})assertThatThrownBy(()->user.recordSignupDateOfBirth(LocalDate.of(year,1,1)))
   .isInstanceOfSatisfying(io.edupilot.global.error.BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(io.edupilot.global.error.ErrorCode.VALIDATION_FAILED));
  assertThat(user.getDateOfBirth()).isNull();assertThat(user.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
 }
 @Test void capturingBirthdateDoesNotInferAgeOrApproval() {
  var user=User.create("synthetic@example.com","hash","Synthetic");LocalDate date=LocalDate.of(2014,1,1);
  user.recordSignupDateOfBirth(date);assertThat(user.getDateOfBirth()).isEqualTo(date);
  assertThat(user.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
 }
 @Test void legacyAccountDateCannotBeOverwrittenDuringLoginOrProfileMutation() {
  var user=User.create("legacy-synthetic@example.com","hash","Synthetic");ReflectionTestUtils.setField(user,"id",1L);
  assertThatThrownBy(()->user.recordSignupDateOfBirth(LocalDate.of(2000,1,1))).isInstanceOf(IllegalStateException.class);
  assertThat(user.getDateOfBirth()).isNull();assertThat(user.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
 }
 @Test void withdrawalClearsBirthdateAndPendingState() {
  var user=User.create("synthetic@example.com","hash","Synthetic");user.recordSignupDateOfBirth(LocalDate.of(2014,1,1));
  ReflectionTestUtils.setField(user,"id",1L);user.beginGuardianVerification();user.withdraw();
  assertThat(user.getDateOfBirth()).isNull();assertThat(user.getAgeVerificationState()).isEqualTo(AgeVerificationState.UNKNOWN);
 }
}
