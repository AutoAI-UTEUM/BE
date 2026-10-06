package io.edupilot.auth;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.user.UserAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

@Component
public class UserAccessGuard {
	private final UserRepository users;
	private final Clock clock;
	private final boolean teamReviewEnabled;
	private String expectedPolicyDigest;
	@Autowired(required = false)
	private GuardianTeamProperties teamPolicy;

	public UserAccessGuard(UserRepository users, Clock clock) {
		this(users, clock, false);
	}

	@Autowired
	public UserAccessGuard(UserRepository users, Clock clock,
		@Value("${edupilot.guardian.team.enabled:false}") boolean teamReviewEnabled) {
		this.users = users;
		this.clock = clock;
		this.teamReviewEnabled = teamReviewEnabled;
	}

	public UserAccessGuard(UserRepository users, Clock clock, boolean teamReviewEnabled, String expectedPolicyDigest) {
		this(users, clock, teamReviewEnabled);
		this.expectedPolicyDigest = expectedPolicyDigest;
	}

	private String currentPolicyDigest() {
		return teamPolicy == null ? expectedPolicyDigest : teamPolicy.ready() ? teamPolicy.configurationDigest() : null;
	}

	public ErrorCode check(AuthenticatedUser principal) {
		// A positive process-local cache cannot observe revocations committed by another instance.
		UserAccessState state = users.findAccessStateById(principal.userId())
			.orElse(new UserAccessState(null, null));
		return authorizationFailure(principal, state.role(), state.status());
	}

	/** Used at business/SSE boundaries; authentication and self-management remain available. */
	public ErrorCode checkBusiness(AuthenticatedUser principal) {
		var state = users.findBusinessAccessStateById(principal.userId()).orElse(null);
		if (state == null) return ErrorCode.TOKEN_INVALID;
		ErrorCode failure = authorizationFailure(principal, state.role(), state.status());
		return failure == null ? state.eligibilityFailure(clock, teamReviewEnabled, currentPolicyDigest()) : failure;
	}

	/** Guardian AI scope and generation are read from committed state, never cached JWT claims. */
	public ErrorCode checkAiBusiness(AuthenticatedUser principal) {
		return checkAiBusiness(principal, null);
	}

	public ErrorCode checkAiBusiness(AuthenticatedUser principal, long expectedGuardianConsentEpoch) {
		return checkAiBusiness(principal, Long.valueOf(expectedGuardianConsentEpoch));
	}

	private ErrorCode checkAiBusiness(AuthenticatedUser principal, Long expectedGuardianConsentEpoch) {
		var state = users.findBusinessAccessStateById(principal.userId()).orElse(null);
		if (state == null) return ErrorCode.TOKEN_INVALID;
		ErrorCode failure = authorizationFailure(principal, state.role(), state.status());
		if (failure != null) return failure;
		if (expectedGuardianConsentEpoch != null && state.guardianConsentEpoch() != expectedGuardianConsentEpoch) {
			return ErrorCode.GUARDIAN_CONSENT_CHANGED;
		}
		return state.aiEligibilityFailure(clock, teamReviewEnabled, currentPolicyDigest());
	}

	private ErrorCode authorizationFailure(AuthenticatedUser principal, UserRole role, UserStatus status) {
		if (status == UserStatus.SUSPENDED) {
			return ErrorCode.ACCOUNT_SUSPENDED;
		}
		if (status != UserStatus.ACTIVE || role != principal.role()) {
			return ErrorCode.TOKEN_INVALID;
		}
		return null;
	}

}
