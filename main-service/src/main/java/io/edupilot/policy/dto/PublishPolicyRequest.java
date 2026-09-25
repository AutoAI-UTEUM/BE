package io.edupilot.policy.dto;

import java.time.Instant;

import io.edupilot.policy.PolicyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublishPolicyRequest(
	@NotNull PolicyType type,
	@NotBlank @Size(max = 20) String version,
	@NotBlank @Size(max = 200) String title,
	@NotBlank String content,
	@Size(max = 1000) String summary,
	@NotNull Instant effectiveAt
) {
}
