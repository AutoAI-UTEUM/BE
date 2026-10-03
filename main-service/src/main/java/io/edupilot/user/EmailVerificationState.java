package io.edupilot.user;

/** UNKNOWN is not evidence of ownership, including for pre-migration accounts. */
public enum EmailVerificationState {
	UNKNOWN, PENDING, VERIFIED
}
