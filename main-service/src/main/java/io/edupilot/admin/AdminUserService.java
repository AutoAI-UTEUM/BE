package io.edupilot.admin;

import java.time.Clock;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.admin.dto.AdminPasswordResetResponse;
import io.edupilot.admin.dto.AdminUserDetailResponse;
import io.edupilot.admin.dto.AdminUserListResponse;
import io.edupilot.admin.dto.AdminUserResponse;
import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserCurrentStateRefresh;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

@Service
public class AdminUserService {
	private static final String PASSWORD_RESET_MESSAGE = "로그인 후 즉시 변경 안내";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final TemporaryPasswordGenerator temporaryPasswordGenerator;
	private final Clock clock;
	@PersistenceContext private EntityManager entityManager;

	public AdminUserService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokenService,
		TemporaryPasswordGenerator temporaryPasswordGenerator,
		Clock clock
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.temporaryPasswordGenerator = temporaryPasswordGenerator;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AdminUserListResponse list(
		String query,
		UserRole role,
		UserStatus status,
		AdminUserSort sort,
		int page,
		int size
	) {
		Page<User> users = userRepository.findAdminUsers(
			normalizedQuery(query),
			role,
			status,
			PageRequest.of(page, size, userSort(sort))
		);
		Page<AdminUserResponse> responses = users.map(AdminUserResponse::from);
		return new AdminUserListResponse(
			responses.getContent(),
			responses.getNumber(),
			responses.getSize(),
			responses.getTotalElements(),
			responses.getTotalPages()
		);
	}

	@Transactional(readOnly = true)
	public AdminUserDetailResponse detail(Long userId) {
		return userRepository.findById(userId)
			.map(AdminUserDetailResponse::from)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	/**
	 * 관리자 조회 전용 원칙의 명시적 예외다. 운영 중 수작업 비밀번호 변경을
	 * 감사 가능한 안전 경로로 바꾸며, 대상자가 즉시 인지하는 행위만 허용한다.
	 */
	@Transactional
	public AdminPasswordResetResponse resetPassword(Long actorUserId, Long targetUserId) {
		User target = userRepository.findById(targetUserId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (!target.isActive()) {
			throw new BusinessException(ErrorCode.PASSWORD_RESET_NOT_ALLOWED);
		}
		if (target.getAuthProvider() != AuthProvider.LOCAL) {
			throw new BusinessException(ErrorCode.PASSWORD_NOT_SUPPORTED);
		}
		if (actorUserId.equals(targetUserId)) {
			throw new BusinessException(ErrorCode.PASSWORD_RESET_NOT_ALLOWED);
		}

		String temporaryPassword = temporaryPasswordGenerator.generate();
		target.changePassword(passwordEncoder.encode(temporaryPassword));
		userRepository.flush();
		refreshTokenService.revokeAll(targetUserId);
		return new AdminPasswordResetResponse(
			temporaryPassword,
			PASSWORD_RESET_MESSAGE
		);
	}

	/** 관리자 조회 전용 원칙의 예외: 계정 정지와 역할 변경을 감사 가능한 경로로 제한한다. */
	@Transactional
	public AdminUserDetailResponse suspend(Long actorUserId, Long targetUserId, String reason) {
		if (actorUserId.equals(targetUserId)) {
			throw new BusinessException(ErrorCode.ADMIN_SELF_MODIFICATION);
		}
		var accounts = lockAdminMutation(actorUserId, targetUserId);
		User target = accounts.target();
		if (!target.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		protectLastAdmin(target, accounts.lockedActiveAdminCount());
		target.suspend(reason.trim(), actorUserId, clock.instant());
		refreshTokenService.revokeAll(targetUserId);
		return AdminUserDetailResponse.from(target);
	}

	@Transactional
	public AdminUserDetailResponse reinstate(Long targetUserId) {
		User target = lockedTarget(targetUserId);
		if (target.getStatus() != UserStatus.SUSPENDED) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		target.reinstate();
		return AdminUserDetailResponse.from(target);
	}

	@Transactional
	public RoleChangeResult changeRole(Long actorUserId, Long targetUserId, UserRole role) {
		if (actorUserId.equals(targetUserId) && role != UserRole.ADMIN) {
			throw new BusinessException(ErrorCode.ADMIN_SELF_MODIFICATION);
		}
		var accounts = lockAdminMutation(actorUserId, targetUserId);
		User target = accounts.target();
		if (target.getStatus() == UserStatus.DELETED) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		UserRole before = target.getRole();
		if (before == role) {
			return new RoleChangeResult(before, AdminUserDetailResponse.from(target));
		}
		if (role != UserRole.ADMIN && target.isActive()) {
			protectLastAdmin(target, accounts.lockedActiveAdminCount());
		}
		target.changeRole(role);
		refreshTokenService.revokeAll(targetUserId);
		return new RoleChangeResult(before, AdminUserDetailResponse.from(target));
	}

	private User lockedTarget(Long userId) {
		return userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
	}

	private LockedAdminMutation lockAdminMutation(Long actorUserId, Long targetUserId) {
		var accounts = new ArrayList<User>();
		// Match TEAM review and consent/report fences: lock existing User IDs in numeric order.
		for (Long id : new TreeSet<>(List.of(actorUserId, targetUserId))) {
			userRepository.findByIdForUpdate(id).ifPresent(account -> {
				// An existing caller transaction may contain a stale managed entity.
				UserCurrentStateRefresh.refreshLocked(entityManager, account, LockModeType.PESSIMISTIC_WRITE);
				accounts.add(account);
			});
		}
		User actor = accounts.stream().filter(account -> account.getId().equals(actorUserId)).findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
		if (actor.getRole() != UserRole.ADMIN || !actor.isActive()) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		User target = accounts.stream().filter(account -> account.getId().equals(targetUserId)).findFirst()
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		// Self removal is rejected above. A different fresh, locked actor remains ACTIVE ADMIN
		// when the target is removed, so this locked count is a sufficient lower bound.
		long lockedActiveAdmins = accounts.stream().filter(account -> account.getRole() == UserRole.ADMIN && account.isActive()).count();
		return new LockedAdminMutation(target, lockedActiveAdmins);
	}

	private void protectLastAdmin(User target, long lockedActiveAdminCount) {
		if (target.getRole() == UserRole.ADMIN && lockedActiveAdminCount <= 1) {
			throw new BusinessException(ErrorCode.LAST_ADMIN_PROTECTED);
		}
	}

	private record LockedAdminMutation(User target, long lockedActiveAdminCount) { }

	public record RoleChangeResult(UserRole before, AdminUserDetailResponse user) {
	}

	private String normalizedQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		return query.trim().toLowerCase(Locale.ROOT);
	}

	private Sort userSort(AdminUserSort sort) {
		return switch (sort == null ? AdminUserSort.RECENT : sort) {
			case RECENT -> Sort.by(
				Sort.Order.desc("createdAt"),
				Sort.Order.desc("id")
			);
			case NAME -> Sort.by(
				Sort.Order.asc("name"),
				Sort.Order.asc("id")
			);
			case RECENT_ACTIVITY_DESC -> Sort.by(
				Sort.Order.desc("lastActiveAt").nullsLast(),
				Sort.Order.desc("id")
			);
			case RECENT_ACTIVITY_ASC -> Sort.by(
				Sort.Order.asc("lastActiveAt").nullsLast(),
				Sort.Order.asc("id")
			);
		};
	}
}
