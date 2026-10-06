package io.edupilot.guardian;

/** A team approval is usable only with current explicit consent, configured mode and a valid expiry. */
public enum AgeVerificationState { UNKNOWN, MANUAL_PENDING, TEAM_APPROVED }
