package io.edupilot.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.user.UserAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@Service
public class SessionStreamAccessGuard {
	private final UserRepository users;
	private final UserAccessGuard accounts;
	private final MaterialAccessService materials;

	public SessionStreamAccessGuard(UserRepository users, UserAccessGuard accounts, MaterialAccessService materials) {
		this.users = users;
		this.accounts = accounts;
		this.materials = materials;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public UserRole captureRole(Long userId) {
		UserRole role = users.findAccessStateById(userId).map(UserAccessState::role)
			.orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
		assertAccount(userId, role);
		return role;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void assertAccessible(Long userId, Long sessionId, UserRole connectedRole) {
		// A turn transaction may already have read an older account or grant snapshot.
		assertAccount(userId, connectedRole);
		materials.assertSessionAccessible(userId, sessionId);
	}

	private void assertAccount(Long userId, UserRole role) {
		ErrorCode failure = accounts.checkBusiness(new AuthenticatedUser(userId, role));
		if (failure != null) throw new BusinessException(failure);
	}
}
