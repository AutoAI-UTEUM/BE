package io.edupilot.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.auth.dto.PasswordResetConfirmRequest;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.EmailTemplates;
import io.edupilot.user.AuthProvider;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

@Service
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
	private static final int TOKEN_BYTES = 32;
	private static final int EXPIRY_MINUTES = 30;
	private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
	private static final String DUMMY_PASSWORD = "password-reset-timing";

	private final UserRepository userRepository;
	private final PasswordResetTokenRepository tokenRepository;
	private final PasswordResetRateLimiter rateLimiter;
	private final PasswordEncoder passwordEncoder;
	private final RefreshTokenService refreshTokenService;
	private final EmailService emailService;
	private final EmailTemplates emailTemplates;
	private final Validator validator;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();
	private final String dummyPasswordHash;

	public PasswordResetService(
		UserRepository userRepository,
		PasswordResetTokenRepository tokenRepository,
		PasswordResetRateLimiter rateLimiter,
		PasswordEncoder passwordEncoder,
		RefreshTokenService refreshTokenService,
		EmailService emailService,
		EmailTemplates emailTemplates,
		Validator validator,
		Clock clock
	) {
		this.userRepository = userRepository;
		this.tokenRepository = tokenRepository;
		this.rateLimiter = rateLimiter;
		this.passwordEncoder = passwordEncoder;
		this.refreshTokenService = refreshTokenService;
		this.emailService = emailService;
		this.emailTemplates = emailTemplates;
		this.validator = validator;
		this.clock = clock;
		this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD);
	}

	@Transactional
	public void request(String email, String ip) {
		String normalizedEmail = normalizeEmail(email);
		if (!rateLimiter.allowRequest(
			normalizedEmail == null ? "<invalid>" : normalizedEmail, ip
		)) {
			log.atWarn()
				.addKeyValue("email", maskEmail(normalizedEmail))
				.addKeyValue("ip", ip)
				.log("Password reset request rate limited");
			logRequest(null, ip);
			return;
		}

		// Apply the same BCrypt work on both account-exists and account-missing paths.
		passwordEncoder.matches(DUMMY_PASSWORD, dummyPasswordHash);
		if (normalizedEmail == null) {
			logRequest(null, ip);
			return;
		}

		User user = userRepository.findByEmailForUpdate(normalizedEmail).orElse(null);
		if (user == null || !user.isActive() || user.getAuthProvider() != AuthProvider.LOCAL) {
			logRequest(null, ip);
			return;
		}

		Instant now = clock.instant();
		tokenRepository.useAllUnusedByUserId(user.getId(), now);
		String rawToken = generateToken();
		tokenRepository.saveAndFlush(PasswordResetToken.create(
			user, hash(rawToken), now.plus(Duration.ofMinutes(EXPIRY_MINUTES)), ip, now
		));
		try {
			emailService.sendAsync(emailTemplates.passwordReset(
				"/reset-password?token=" + rawToken, EXPIRY_MINUTES
			).to(user.getEmail()));
		} catch (RuntimeException exception) {
			log.atWarn()
				.addKeyValue("userId", user.getId())
				.addKeyValue("errorType", exception.getClass().getSimpleName())
				.log("Password reset mail could not be queued");
		}
		logRequest(user.getId(), ip);
	}

	@Transactional
	public void confirm(String rawToken, String newPassword, String ip) {
		if (!rateLimiter.allowConfirm(ip)) {
			throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
		}
		if (rawToken == null || !TOKEN_FORMAT.matcher(rawToken).matches()) {
			throw invalidToken();
		}

		String tokenHash = hash(rawToken);
		Long userId = tokenRepository.findUserIdByTokenHash(tokenHash)
			.orElseThrow(this::invalidToken);
		User user = userRepository.findByIdForUpdate(userId)
			.orElseThrow(this::invalidToken);
		PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(tokenHash)
			.orElseThrow(this::invalidToken);
		Instant now = clock.instant();
		if (!token.isUsable(now) || !user.isActive()
			|| user.getAuthProvider() != AuthProvider.LOCAL) {
			throw invalidToken();
		}
		var violations = validator.validate(new PasswordResetConfirmRequest(rawToken, newPassword));
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException(violations);
		}
		if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.PASSWORD_REUSE_NOT_ALLOWED);
		}

		user.changePassword(passwordEncoder.encode(newPassword));
		userRepository.flush();
		token.use(now);
		// revokeAll invalidates both refresh tokens and their auth-session families.
		refreshTokenService.revokeAll(userId);
		log.atInfo()
			.addKeyValue("action", "PASSWORD_RESET_COMPLETED")
			.addKeyValue("userId", userId)
			.addKeyValue("ip", ip)
			.log("Password reset completed");
	}

	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private String generateToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String normalizeEmail(String email) {
		if (email == null || email.isBlank() || email.length() > 255) {
			return null;
		}
		return AuthService.normalizeEmail(email);
	}

	private String maskEmail(String email) {
		if (email == null || email.isBlank()) {
			return "***";
		}
		int at = email.indexOf('@');
		return at < 0 ? "***" : email.charAt(0) + "***" + email.substring(at);
	}

	private void logRequest(Long userId, String ip) {
		log.atInfo()
			.addKeyValue("action", "PASSWORD_RESET_REQUESTED")
			.addKeyValue("userId", userId)
			.addKeyValue("ip", ip)
			.log("Password reset requested");
	}

	private BusinessException invalidToken() {
		return new BusinessException(ErrorCode.RESET_TOKEN_INVALID);
	}
}
