package io.edupilot.auth;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;

@Component
public class UserAccessGuard {
	private final UserRepository users;

	public UserAccessGuard(UserRepository users) {
		this.users = users;
	}

	public ErrorCode check(AuthenticatedUser principal) {
		// A positive process-local cache cannot observe revocations committed by another instance.
		UserAccessState state = users.findAccessStateById(principal.userId())
			.orElse(new UserAccessState(null, null));
		if (state.status() == UserStatus.SUSPENDED) {
			return ErrorCode.ACCOUNT_SUSPENDED;
		}
		if (state.status() != UserStatus.ACTIVE || state.role() != principal.role()) {
			return ErrorCode.TOKEN_INVALID;
		}
		return null;
	}

}
