package io.edupilot.user;

import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.RefreshTokenService;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.dto.UserResponse;

@Service
public class UserService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final List<UserWithdrawalHook> withdrawalHooks;

	public UserService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokenService,
		List<UserWithdrawalHook> withdrawalHooks
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.withdrawalHooks = withdrawalHooks;
	}

	@Transactional(readOnly = true)
	public UserResponse me(Long userId) {
		User user = activeUser(userId);
		return UserResponse.from(user);
	}

	@Transactional
	public void withdraw(Long userId, String password) {
		User user = activeUser(userId);
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		// TODO Epic 3·4에서 자료와 세션 논리 삭제 hook 구현체를 등록한다.
		withdrawalHooks.forEach(hook -> hook.onWithdraw(userId));
		refreshTokenService.revokeAll(userId);
		user.withdraw();
	}

	private User activeUser(Long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		return user;
	}
}
