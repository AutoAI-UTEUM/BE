package io.edupilot.guardian;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/** 외부 AI 전송과 늦은 결과 저장 사이의 보호자 동의 세대를 확인합니다. */
@Service
public class GuardianConsentFence {

	private final UserRepository users;
	private final LearningMaterialRepository materials;
	private final LearningSessionRepository sessions;
	private final Clock clock;
	@PersistenceContext
	private EntityManager entityManager;
	private final GuardianTeamProperties policy;

	public GuardianConsentFence(UserRepository users, LearningMaterialRepository materials,
		LearningSessionRepository sessions, Clock clock, GuardianTeamProperties policy) {
		this.users = users;
		this.materials = materials;
		this.sessions = sessions;
		this.clock = clock;
		this.policy = policy;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Snapshot capture(Long userId) {
		UserBusinessAccessState state = current(userId);
		requireEligible(state);
		return new Snapshot(userId, state.guardianConsentEpoch());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Snapshot captureMaterialOwner(Long materialId) {
		Long ownerId = materials.findById(materialId)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATERIAL_NOT_FOUND)).getOwnerId();
		return capture(ownerId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Snapshot captureSessionOwner(Long sessionId) {
		Long ownerId = sessions.findById(sessionId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND)).getUserId();
		return capture(ownerId);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public void assertCurrent(Snapshot snapshot) {
		requireCurrent(Objects.requireNonNull(snapshot), current(snapshot.userId()));
	}

	/** 저장 서비스의 REQUIRED 트랜잭션과 같은 계정 잠금을 유지합니다. */
	@Transactional(propagation = Propagation.REQUIRED)
	public <T> T complete(Snapshot snapshot, Supplier<T> save) {
		return complete(List.of(Objects.requireNonNull(snapshot)), save);
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public <T> T complete(List<Snapshot> snapshots, Supplier<T> save) {
		Objects.requireNonNull(save);
		if (snapshots == null || snapshots.isEmpty()) throw new IllegalArgumentException("동의 확인 대상이 필요합니다.");
		var byUser = new LinkedHashMap<Long, Snapshot>();
		for (Snapshot snapshot : snapshots) {
			Objects.requireNonNull(snapshot);
			Snapshot previous = byUser.putIfAbsent(snapshot.userId(), snapshot);
			if (previous != null && previous.guardianConsentEpoch() != snapshot.guardianConsentEpoch()) {
				throw new BusinessException(ErrorCode.GUARDIAN_CONSENT_CHANGED);
			}
		}
		for (Snapshot snapshot : byUser.values().stream().sorted(Comparator.comparing(Snapshot::userId)).toList()) {
			User user = users.findByIdForBusinessAccess(snapshot.userId())
				.orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
			// 1차 캐시와 REPEATABLE_READ의 이전 조회 결과를 신뢰하지 않고 현재 잠긴 행을 읽습니다.
			entityManager.refresh(user, LockModeType.PESSIMISTIC_READ);
			requireCurrent(snapshot, UserBusinessAccessState.from(user));
		}
		return save.get();
	}

	private UserBusinessAccessState current(Long userId) {
		return users.findBusinessAccessStateById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
	}

	private void requireCurrent(Snapshot snapshot, UserBusinessAccessState state) {
		if (state.guardianConsentEpoch() != snapshot.guardianConsentEpoch()) {
			throw new BusinessException(ErrorCode.GUARDIAN_CONSENT_CHANGED);
		}
		requireEligible(state);
	}

	private void requireEligible(UserBusinessAccessState state) {
		if (state.status() == UserStatus.SUSPENDED) throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		if (state.status() != UserStatus.ACTIVE) throw new BusinessException(ErrorCode.USER_INACTIVE);
		ErrorCode failure = state.aiEligibilityFailure(clock, policy.ready(), policy.configurationDigest());
		if (failure != null) throw new BusinessException(failure);
	}

	public static boolean isConsentFailure(BusinessException exception) {
		return switch (exception.errorCode()) {
			case GUARDIAN_CONSENT_CHANGED, GUARDIAN_AI_CONSENT_REQUIRED, GUARDIAN_VERIFICATION_PENDING,
				AGE_VERIFICATION_REQUIRED, EMAIL_VERIFICATION_REQUIRED, USER_INACTIVE, ACCOUNT_SUSPENDED -> true;
			default -> false;
		};
	}

	public record Snapshot(Long userId, long guardianConsentEpoch) {
		public Snapshot { Objects.requireNonNull(userId); }
		@Override public String toString() { return "GuardianConsentFence.Snapshot[REDACTED]"; }
	}
}
