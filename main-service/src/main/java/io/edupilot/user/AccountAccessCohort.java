package io.edupilot.user;

/** An access policy exception for accounts present at the V58 migration boundary, not verification evidence. */
public enum AccountAccessCohort {
	LEGACY_EXEMPT,
	NEW_SIGNUP
}
