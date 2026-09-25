package io.edupilot.policy.dto;

import io.edupilot.policy.PolicyDocument;
import io.edupilot.policy.PolicyType;

public record PendingPolicyConsent(PolicyType type, String version, String title) {
	public static PendingPolicyConsent from(PolicyDocument document) {
		return new PendingPolicyConsent(
			document.getType(), document.getVersion(), document.getTitle()
		);
	}
}
