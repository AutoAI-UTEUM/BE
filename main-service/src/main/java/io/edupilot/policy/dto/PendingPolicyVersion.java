package io.edupilot.policy.dto;

import io.edupilot.policy.PolicyType;

public record PendingPolicyVersion(PolicyType type, String version) {
}
