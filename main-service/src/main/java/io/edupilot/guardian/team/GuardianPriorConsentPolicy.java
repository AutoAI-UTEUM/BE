package io.edupilot.guardian.team;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.bind.DefaultValue;

/** Collection methods and age limits require an explicit reviewed policy; none is inferred from stored records. */
public record GuardianPriorConsentPolicy(@DefaultValue("false") boolean enabled,
	@DefaultValue("false") boolean policyConfirmed, List<Method> methods, String policyReference, Duration maxAge) {
	public GuardianPriorConsentPolicy {
		methods = methods == null ? List.of() : List.copyOf(methods);
	}
	static GuardianPriorConsentPolicy disabled() { return new GuardianPriorConsentPolicy(false, false, null, null, null); }
	public boolean ready() {
		return enabled && policyConfirmed && !methods.isEmpty() && methods.stream().distinct().count() == methods.size()
			&& policyReference != null && policyReference.matches("[A-Za-z0-9_.:-]{1,100}")
			&& maxAge != null && !maxAge.isZero() && !maxAge.isNegative();
	}
	String snapshot() {
		return methods.stream().sorted().toList() + ":" + policyReference + ":" + maxAge;
	}
	public enum Method { EMAIL_REPLY, PHONE, SIGNED_DOCUMENT, IN_PERSON }
}
