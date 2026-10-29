package io.edupilot.user;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.edupilot.auth.GoogleIdTokenVerifier;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.dto.WithdrawRequest;

@Service
public class UserWithdrawalService {
	private final UserService userService;
	private final GoogleIdTokenVerifier googleVerifier;

	public UserWithdrawalService(UserService userService, GoogleIdTokenVerifier googleVerifier) {
		this.userService = userService;
		this.googleVerifier = googleVerifier;
	}

	/** Verify with the provider before opening the account deletion transaction. */
	public void withdraw(Long userId, WithdrawRequest request) {
		boolean password = StringUtils.hasText(request.password());
		boolean google = StringUtils.hasText(request.googleIdToken());
		if (password == google) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (google) {
			var profile = googleVerifier.verify(request.googleIdToken());
			userService.withdrawGoogle(userId, profile.sub());
		} else {
			userService.withdraw(userId, request.password());
		}
	}
}
