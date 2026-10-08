package io.edupilot.guardian;

import io.edupilot.user.User;

/** Replaceable intake boundary. Approval/evidence verification is deliberately absent. */
public interface GuardianIntakeProvider {
 GuardianVerificationRequest request(User user);
}
