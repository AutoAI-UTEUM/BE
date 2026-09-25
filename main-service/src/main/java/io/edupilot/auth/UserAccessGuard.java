package io.edupilot.auth;

import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

@Component
public class UserAccessGuard {
	private final UserRepository users;
	private final Cache<Long, AccessState> states;

	@Autowired
	public UserAccessGuard(UserRepository users) {
		this(users, Ticker.systemTicker());
	}

	UserAccessGuard(UserRepository users, Ticker ticker) {
		this.users = users;
		this.states = Caffeine.newBuilder()
			.maximumSize(100_000)
			.expireAfterWrite(Duration.ofMinutes(1))
			.ticker(ticker)
			.build();
	}

	public ErrorCode check(AuthenticatedUser principal) {
		AccessState state = states.get(principal.userId(), id -> users.findById(id)
			.map(AccessState::from)
			.orElse(new AccessState(null, null)));
		if (state.status() == UserStatus.SUSPENDED) {
			return ErrorCode.ACCOUNT_SUSPENDED;
		}
		if (state.status() != UserStatus.ACTIVE || state.role() != principal.role()) {
			return ErrorCode.TOKEN_INVALID;
		}
		return null;
	}

	public void invalidateAfterCommit(Long userId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			states.invalidate(userId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				states.invalidate(userId);
			}
		});
	}

	private record AccessState(UserRole role, UserStatus status) {
		static AccessState from(User user) {
			return new AccessState(user.getRole(), user.getStatus());
		}
	}
}
