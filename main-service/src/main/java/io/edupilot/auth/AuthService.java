package io.edupilot.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.auth.RefreshTokenService.RotationResult;
import io.edupilot.auth.RefreshTokenService.RotationStatus;
import io.edupilot.auth.dto.AccessTokenResponse;
import io.edupilot.auth.dto.EmailAvailabilityResponse;
import io.edupilot.auth.dto.GoogleLoginRequest;
import io.edupilot.auth.dto.LoginRequest;
import io.edupilot.auth.dto.LoginResponse;
import io.edupilot.auth.dto.SignupRequest;
import io.edupilot.auth.dto.SignupResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.dto.UserResponse;

@Service
public class AuthService {

	private static final String TOKEN_TYPE = "Bearer";
	private static final Set<String> SUPPORTED_TERMS_VERSIONS = Set.of("2026-07-01");
	private static final Set<String> SUPPORTED_PRIVACY_VERSIONS = Set.of("2026-07-01");

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider jwtTokenProvider;
	private final RefreshTokenService refreshTokenService;
	private final GoogleIdTokenVerifier googleIdTokenVerifier;
	private final GoogleAccountService googleAccountService;
	private final Clock clock;

	public AuthService(
		UserRepository userRepository,
		PasswordEncoder passwordEncoder,
		JwtTokenProvider jwtTokenProvider,
		RefreshTokenService refreshTokenService,
		GoogleIdTokenVerifier googleIdTokenVerifier,
		GoogleAccountService googleAccountService,
		Clock clock
	) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenProvider = jwtTokenProvider;
		this.refreshTokenService = refreshTokenService;
		this.googleIdTokenVerifier = googleIdTokenVerifier;
		this.googleAccountService = googleAccountService;
		this.clock = clock;
	}

	@Transactional
	public SignupResponse signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		if (!isEmailAvailable(email)) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}

		Consent consent = validateConsent(
			request.termsVersion(),
			request.privacyVersion(),
			clock
		);
		User user = User.create(
			email,
			passwordEncoder.encode(request.password()),
			request.name().trim(),
			request.role().toUserRole(),
			normalizeOptional(request.affiliation()),
			Boolean.TRUE.equals(request.learningEmailOptIn()),
			consent.termsVersion(),
			consent.privacyVersion(),
			consent.consentedAt()
		);
		try {
			User savedUser = userRepository.saveAndFlush(user);
			return SignupResponse.from(savedUser);
		} catch (DataIntegrityViolationException exception) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
		}
	}

	@Transactional
	public LoginResult login(LoginRequest request) {
		User user = userRepository.findByEmail(normalizeEmail(request.email()))
			.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
		if (!user.isActive()) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
		}

		return issueLogin(user);
	}

	public LoginResult googleLogin(GoogleLoginRequest request) {
		GoogleProfile profile = googleIdTokenVerifier.verify(request.idToken());
		return issueLogin(googleAccountService.resolve(request, profile));
	}

	public RefreshResult refresh(String rawToken) {
		RotationResult rotation = refreshTokenService.rotate(rawToken);
		if (rotation.status() == RotationStatus.INACTIVE) {
			throw new BusinessException(ErrorCode.USER_INACTIVE);
		}
		if (rotation.status() != RotationStatus.SUCCESS) {
			throw new BusinessException(ErrorCode.TOKEN_INVALID);
		}

		String accessToken = jwtTokenProvider.createAccessToken(rotation.user());
		AccessTokenResponse response = new AccessTokenResponse(
			accessToken,
			TOKEN_TYPE,
			jwtTokenProvider.accessTokenExpiresInSeconds()
		);
		return new RefreshResult(response, rotation.rawToken());
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
		String refreshToken = refreshTokenService.issue(user);
		LoginResponse response = new LoginResponse(
			accessToken,
			TOKEN_TYPE,
			jwtTokenProvider.accessTokenExpiresInSeconds(),
			UserResponse.from(user)
		);
		return new LoginResult(response, refreshToken);
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	static Consent validateConsent(
		String termsVersion,
		String privacyVersion,
		Clock clock
	) {
		String normalizedTerms = normalizeOptional(termsVersion);
		String normalizedPrivacy = normalizeOptional(privacyVersion);
		if ((normalizedTerms == null) != (normalizedPrivacy == null)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (normalizedTerms == null) {
			return new Consent(null, null, null);
		}
		if (!SUPPORTED_TERMS_VERSIONS.contains(normalizedTerms)
			|| !SUPPORTED_PRIVACY_VERSIONS.contains(normalizedPrivacy)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return new Consent(normalizedTerms, normalizedPrivacy, clock.instant());
	}

	static String normalizeOptional(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	record Consent(
		String termsVersion,
		String privacyVersion,
		Instant consentedAt
	) {
	}

	public record LoginResult(LoginResponse response, String refreshToken) {
	}

	public record RefreshResult(AccessTokenResponse response, String refreshToken) {
	}
}
