package io.edupilot.policy.dto;

import java.time.Instant;

import io.edupilot.policy.PolicyDocument;
import io.edupilot.policy.PolicyType;

public record PolicyDocumentSummary(
	PolicyType type, String version, String title, Instant effectiveAt, String summary
) {
	public static PolicyDocumentSummary from(PolicyDocument document) {
		return new PolicyDocumentSummary(
			document.getType(), document.getVersion(), document.getTitle(),
			document.getEffectiveAt(), document.getSummary()
		);
	}
}
