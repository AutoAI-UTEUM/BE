package io.edupilot.policy.dto;

import java.time.Instant;

import io.edupilot.policy.PolicyConsent;
import io.edupilot.policy.PolicyType;

public record AgreedPolicyConsent(PolicyType type, String version, Instant agreedAt) {
	public static AgreedPolicyConsent from(PolicyConsent consent) {
		return new AgreedPolicyConsent(
			consent.getPolicyType(), consent.getPolicyVersion(), consent.getAgreedAt()
		);
	}
}
