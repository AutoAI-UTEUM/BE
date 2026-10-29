package io.edupilot.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.AuthenticatedUser;
import io.edupilot.auth.UserAccessGuard;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.MaterialAccessService;
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
		return captureAccess(userId).role();
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public Access captureAccess(Long userId) {
		var state = users.findBusinessAccessStateById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
		Access access = new Access(state.role(), state.guardianConsentEpoch());
		// 상태 캡처와 이용 자격 검사 사이에 동의가 변경돼도 요청을 차단한다.
		assertAccount(userId, access);
		return access;
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void assertAccessible(Long userId, Long sessionId, UserRole connectedRole) {
		// 동기 검사 호환용이다. 실제 턴과 SSE는 캡처한 Access를 반드시 전달한다.
		ErrorCode failure = accounts.checkAiBusiness(new AuthenticatedUser(userId, connectedRole));
		if (failure != null) throw new BusinessException(failure);
		materials.assertSessionAccessible(userId, sessionId);
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
	public void assertAccessible(Long userId, Long sessionId, Access access) {
		// A turn transaction may already have read an older account or grant snapshot.
		assertAccount(userId, access);
		materials.assertSessionAccessible(userId, sessionId);
	}

	private void assertAccount(Long userId, Access access) {
		ErrorCode failure = accounts.checkAiBusiness(new AuthenticatedUser(userId, access.role()),
			access.guardianConsentEpoch());
		if (failure != null) throw new BusinessException(failure);
	}

	public record Access(UserRole role, long guardianConsentEpoch) { }
}
