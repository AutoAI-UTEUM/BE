package io.edupilot.policy.dto;

import java.time.Instant;

import io.edupilot.policy.PolicyDocument;
import io.edupilot.policy.PolicyType;

public record PolicyDocumentResponse(
	PolicyType type, String version, String title, String content,
	String summary, Instant effectiveAt
) {
	public static PolicyDocumentResponse from(PolicyDocument document) {
		return new PolicyDocumentResponse(
			document.getType(), document.getVersion(), document.getTitle(),
			document.getContent(), document.getSummary(), document.getEffectiveAt()
		);
	}
}
