package io.edupilot.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;

/** Checks current committed state; a JWT or cached account status is not ownership evidence. */
@Service
public class EmailVerificationGate {
	private final UserRepository users;
	private final LearningMaterialRepository materials;
	private final LearningSessionRepository sessions;
	public EmailVerificationGate(UserRepository users, LearningMaterialRepository materials, LearningSessionRepository sessions) {
		this.users = users;
		this.materials = materials;
		this.sessions = sessions;
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireVerified(Long userId) {
		verify(userId);
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireMaterialOwnerVerified(Long materialId) {
		var material = materials.findById(materialId).orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		verify(material.getOwnerId());
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireSessionOwnerVerified(Long sessionId) {
		var session = sessions.findById(sessionId).orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		verify(session.getUserId());
	}
	private void verify(Long userId) {
		User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
		if (user.getStatus() == UserStatus.SUSPENDED) {
			throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		}
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		if (!user.isEmailVerified()) {
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		}
	}
}
