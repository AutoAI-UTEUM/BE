package io.edupilot.auth;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.team.GuardianTeamProperties;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserBusinessAccessState;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserStatus;

/** Checks committed email and age/guardian eligibility at protected API and AI boundaries. */
@Service
public class EmailVerificationGate {
	private final UserRepository users;
	private final LearningMaterialRepository materials;
	private final LearningSessionRepository sessions;
	private final Clock clock;
	@Value("${edupilot.guardian.team.enabled:false}")
	private boolean teamReviewEnabled;
	@Autowired(required = false)
	private GuardianTeamProperties teamPolicy;
	public EmailVerificationGate(UserRepository users, LearningMaterialRepository materials, LearningSessionRepository sessions, Clock clock) {
		this.users = users;
		this.materials = materials;
		this.sessions = sessions;
		this.clock = clock;
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireVerified(Long userId) {
		verify(userId, false);
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireAiVerified(Long userId) {
		verify(userId, true);
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireMaterialOwnerVerified(Long materialId) {
		var material = materials.findById(materialId).orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));
		verify(material.getOwnerId(), true);
	}
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void requireSessionOwnerVerified(Long sessionId) {
		var session = sessions.findById(sessionId).orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
		verify(session.getUserId(), true);
	}
	private void verify(Long userId, boolean aiPurpose) {
		User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
		if (user.getStatus() == UserStatus.SUSPENDED) {
			throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		}
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		var state = UserBusinessAccessState.from(user);
		String policyDigest = teamPolicy != null && teamPolicy.ready() ? teamPolicy.configurationDigest() : null;
		ErrorCode failure = aiPurpose ? state.aiEligibilityFailure(clock, teamReviewEnabled, policyDigest)
			: state.eligibilityFailure(clock, teamReviewEnabled, policyDigest);
		if (failure != null) throw new BusinessException(failure);
	}
}
