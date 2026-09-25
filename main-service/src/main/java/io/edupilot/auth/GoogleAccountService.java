package io.edupilot.auth;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.auth.dto.SignupRole;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.policy.PolicyService;
import io.edupilot.policy.PolicyService.SignupSelection;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class GoogleAccountService {

	private static final String GOOGLE_PASSWORD_SENTINEL = "!oauth:google";

	private final UserRepository userRepository;
	private final PolicyService policyService;

	public GoogleAccountService(UserRepository userRepository, PolicyService policyService) {
		this.userRepository = userRepository;
		this.policyService = policyService;
	}

	@Transactional
	public User resolve(
		GoogleLoginRequest request, GoogleProfile profile, String ip, String userAgent
	) {
		User user = userRepository.findByGoogleSub(profile.sub()).orElse(null);
		if (user != null) {
			assertActive(user);
			return user;
		}

		String normalizedEmail = AuthService.normalizeEmail(profile.email());
		user = userRepository.findByEmail(normalizedEmail).orElse(null);
		if (user != null) {
			assertActive(user);
			user.linkGoogle(profile.sub());
			try {
				userRepository.flush();
			} catch (DataIntegrityViolationException exception) {
				throw new BusinessException(ErrorCode.TOKEN_INVALID);
			}
			return user;
		}

		SignupRole role = requiredSignupRole(request);
		SignupSelection consent = policyService.validateSignup(request.consents());
		User newUser = User.createGoogle(
			normalizedEmail,
			GOOGLE_PASSWORD_SENTINEL,
			profile.name().trim(),
			role.toUserRole(),
			AuthService.normalizeOptional(request.affiliation()),
			Boolean.TRUE.equals(request.learningEmailOptIn()),
			consent.termsVersion(),
			consent.privacyVersion(),
			consent.agreedAt(),
			profile.sub()
		);
		User saved;
		try {
			saved = userRepository.saveAndFlush(newUser);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
		policyService.recordSignup(saved, consent, ip, userAgent);
		return saved;
	}

	private SignupRole requiredSignupRole(GoogleLoginRequest request) {
		if (!hasText(request.role())) {
			throw new BusinessException(ErrorCode.SIGNUP_REQUIRED);
		}
		try {
			return SignupRole.valueOf(request.role().trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private void assertActive(User user) {
		if (user.getStatus() == io.edupilot.user.UserStatus.SUSPENDED) {
			throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		}
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
	}
}
