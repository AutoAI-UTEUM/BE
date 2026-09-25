package io.edupilot.policy.dto;

import java.util.List;

public record UserPolicyConsentsResponse(
	List<PendingPolicyConsent> pending,
	List<AgreedPolicyConsent> agreed
) {
}
