package io.edupilot.auth;

import java.time.Clock;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

@Component
public class UserAccessGuard {
	private final UserRepository users;
	private final Clock clock;

	public UserAccessGuard(UserRepository users, Clock clock) {
		this.users = users;
		this.clock = clock;
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
		return failure == null ? state.eligibilityFailure(clock) : failure;
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
