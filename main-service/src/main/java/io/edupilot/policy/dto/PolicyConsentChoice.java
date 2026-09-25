package io.edupilot.policy.dto;

import io.edupilot.policy.PolicyType;

public record PolicyConsentChoice(PolicyType type, String version) {
}
