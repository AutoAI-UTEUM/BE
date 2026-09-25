package io.edupilot.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.RefreshTokenService.ActivityResult;
import io.edupilot.auth.RefreshTokenService.IssueResult;
import io.edupilot.auth.RefreshTokenService.RotationResult;
import io.edupilot.auth.RefreshTokenService.SessionStatus;
import io.edupilot.auth.dto.AccessTokenResponse;
import io.edupilot.auth.dto.AuthSessionResponse;
import io.edupilot.auth.dto.EmailAvailabilityResponse;
import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.auth.dto.LoginRequest;
import io.edupilot.auth.dto.LoginResponse;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.policy.PolicyService;
import io.edupilot.policy.PolicyService.SignupSelection;
import io.edupilot.user.User;
import io.edupilot.user.UserActivityTracker;
import io.edupilot.user.UserRepository;
import io.edupilot.user.dto.UserResponse;

@Service
public class AuthService {

	private static final String TOKEN_TYPE = "Bearer";
	private static final String DUMMY_PASSWORD = "login-missing-account";

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final RefreshTokenService refreshTokenService;
	private final GoogleIdTokenVerifier googleIdTokenVerifier;
	private final GoogleAccountService googleAccountService;
	private final UserActivityTracker userActivityTracker;
	private final LoginAttemptLimiter loginAttemptLimiter;
	private final PolicyService policyService;
	private final String dummyPasswordHash;
	private final Clock clock;

	public AuthService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		JwtTokenProvider jwtTokenProvider,
		RefreshTokenService refreshTokenService,
		GoogleIdTokenVerifier googleIdTokenVerifier,
		GoogleAccountService googleAccountService,
		UserActivityTracker userActivityTracker,
		LoginAttemptLimiter loginAttemptLimiter,
		PolicyService policyService,
		Clock clock
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenProvider = jwtTokenProvider;
		this.refreshTokenService = refreshTokenService;
		this.googleIdTokenVerifier = googleIdTokenVerifier;
		this.googleAccountService = googleAccountService;
		this.userActivityTracker = userActivityTracker;
		this.loginAttemptLimiter = loginAttemptLimiter;
		this.policyService = policyService;
		this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD);
		this.clock = clock;
	}

	@Transactional
	public SignupResponse signup(SignupRequest request, String ip, String userAgent) {
		String email = normalizeEmail(request.email());
		if (!isEmailAvailable(email)) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}

		SignupSelection consent = policyService.validateSignup(request.consents());
		User user = User.create(
			email,
			passwordEncoder.encode(request.password()),
			request.name().trim(),
			request.role().toUserRole(),
			normalizeOptional(request.affiliation()),
			Boolean.TRUE.equals(request.learningEmailOptIn()),
			consent.termsVersion(),
			consent.privacyVersion(),
			consent.agreedAt()
		);
		User savedUser;
		try {
			savedUser = userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
		policyService.recordSignup(savedUser, consent, ip, userAgent);
		return SignupResponse.from(savedUser);
	}

	@Transactional
	public LoginResult login(LoginRequest request, String ip) {
		String email = normalizeEmail(request.email());
		loginAttemptLimiter.checkLogin(email, ip);
		User user = userRepository.findByEmail(email).orElse(null);
		String passwordHash = user == null ? dummyPasswordHash : user.getPasswordHash();
		if (!passwordEncoder.matches(request.password(), passwordHash) || user == null) {
			loginAttemptLimiter.recordLoginFailure(email, ip);
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}
		if (user.getStatus() == io.edupilot.user.UserStatus.SUSPENDED) {
			throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		}
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		loginAttemptLimiter.recordLoginSuccess(email);
		return issueLogin(user);
	}

	public LoginResult googleLogin(GoogleLoginRequest request, String ip, String userAgent) {
		GoogleProfile profile = googleIdTokenVerifier.verify(request.idToken());
		return issueLogin(googleAccountService.resolve(request, profile, ip, userAgent));
	}

	public RefreshResult refresh(String rawToken, String ip) {
		loginAttemptLimiter.checkRefresh(ip);
		RotationResult rotation = refreshTokenService.rotate(rawToken);
		if (rotation.status() != SessionStatus.SUCCESS) {
			loginAttemptLimiter.recordRefreshFailure(ip);
		}
		throwIfSessionUnavailable(rotation.status());

		String accessToken = jwtTokenProvider.createAccessToken(rotation.user());
		AccessTokenResponse response = new AccessTokenResponse(
			accessToken,
			TOKEN_TYPE,
			jwtTokenProvider.accessTokenExpiresInSeconds(),
			AuthSessionResponse.from(rotation.session(), rotation.idleTtl())
		);
		userActivityTracker.track(rotation.user().getId());
		return new RefreshResult(
			response,
			rotation.rawToken(),
			cookieMaxAge(rotation.session())
		);
	}

	public AuthSessionResponse recordActivity(Long userId, String rawToken) {
		ActivityResult result = refreshTokenService.recordActivity(userId, rawToken);
		throwIfSessionUnavailable(result.status());
		return AuthSessionResponse.from(result.session(), result.idleTtl());
	}

	public void logout(String rawToken) {
		refreshTokenService.logout(rawToken);
	}

	@Transactional(readOnly = true)
	public EmailAvailabilityResponse emailAvailability(String email) {
		String normalizedEmail = normalizeEmail(email);
		return new EmailAvailabilityResponse(isEmailAvailable(normalizedEmail));
	}

	private boolean isEmailAvailable(String normalizedEmail) {
		return !userRepository.existsByEmail(normalizedEmail);
	}

	private LoginResult issueLogin(User user) {
		String accessToken = jwtTokenProvider.createAccessToken(user);
		IssueResult issue = refreshTokenService.issue(user);
		LoginResponse response = new LoginResponse(
			accessToken,
			TOKEN_TYPE,
			jwtTokenProvider.accessTokenExpiresInSeconds(),
			UserResponse.from(user),
			AuthSessionResponse.from(issue.session(), issue.idleTtl()),
			policyService.pendingForLogin(user.getId())
		);
		return new LoginResult(
			response,
			issue.rawToken(),
			Duration.between(
				issue.session().getLastActivityAt(),
				issue.session().getAbsoluteExpiresAt()
			)
		);
	}

	private void throwIfSessionUnavailable(SessionStatus status) {
		switch (status) {
			case SUCCESS -> {
				return;
			}
			case INACTIVE -> throw new BusinessException(ErrorCode.USER_INACTIVE);
			case SUSPENDED -> throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
			case IDLE_EXPIRED -> throw new BusinessException(
				ErrorCode.AUTH_SESSION_IDLE_EXPIRED
			);
			case ABSOLUTE_EXPIRED -> throw new BusinessException(
				ErrorCode.AUTH_SESSION_ABSOLUTE_EXPIRED
			);
			case INVALID -> throw new BusinessException(ErrorCode.TOKEN_INVALID);
		}
	}

	private Duration cookieMaxAge(AuthSession session) {
		Duration remaining = Duration.between(clock.instant(), session.getAbsoluteExpiresAt());
		return remaining.isNegative() ? Duration.ZERO : remaining;
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	static String normalizeOptional(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	public record LoginResult(
		LoginResponse response,
		String refreshToken,
		Duration cookieMaxAge
	) {
	}

	public record RefreshResult(
		AccessTokenResponse response,
		String refreshToken,
		Duration cookieMaxAge
	) {
	}
}
