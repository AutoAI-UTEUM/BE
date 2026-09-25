package io.edupilot.policy.dto;

import java.util.List;

public record PolicyConsentsRequest(List<PolicyConsentChoice> consents) {
}
