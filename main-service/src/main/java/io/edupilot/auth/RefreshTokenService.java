package io.edupilot.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.edupilot.user.User;

@Service
public class RefreshTokenService {

	private static final int TOKEN_BYTES = 32;

	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtProperties jwtProperties;
	private final Clock clock;
	private final SecureRandom secureRandom;

	public RefreshTokenService(
		RefreshTokenRepository refreshTokenRepository,
		JwtProperties jwtProperties,
		Clock clock
	) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.jwtProperties = jwtProperties;
		this.clock = clock;
		this.secureRandom = new SecureRandom();
	}

	@Transactional
	public String issue(User user) {
		String rawToken = generateToken();
		refreshTokenRepository.save(new RefreshToken(
			user,
			hash(rawToken),
			clock.instant().plus(jwtProperties.refreshTokenTtl())
		));
		return rawToken;
	}

	@Transactional
	public RotationResult rotate(String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			return RotationResult.invalid();
		}

		Instant now = clock.instant();
		RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
			.orElse(null);
		if (token == null) {
			return RotationResult.invalid();
		}

		User user = token.getUser();
		if (!user.isActive()) {
			refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
			return RotationResult.inactive();
		}
		if (token.isRevoked()) {
			refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
			return RotationResult.invalid();
		}
		if (!token.getExpiresAt().isAfter(now)) {
			token.revoke(now);
			return RotationResult.invalid();
		}

		token.revoke(now);
		String newRawToken = generateToken();
		refreshTokenRepository.save(new RefreshToken(
			user,
			hash(newRawToken),
			now.plus(jwtProperties.refreshTokenTtl())
		));
		return RotationResult.success(user, newRawToken);
	}

	@Transactional
	public void logout(String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			return;
		}
		refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
			.ifPresent(token -> token.revoke(clock.instant()));
	}

	@Transactional
	public void revokeAll(Long userId) {
		refreshTokenRepository.revokeAllActiveByUserId(userId, clock.instant());
	}

	String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private String generateToken() {
		byte[] token = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(token);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	public enum RotationStatus {
		SUCCESS,
		INVALID,
		INACTIVE
	}

	public record RotationResult(
		RotationStatus status,
		User user,
		String rawToken
	) {
		static RotationResult success(User user, String rawToken) {
			return new RotationResult(RotationStatus.SUCCESS, user, rawToken);
		}

		static RotationResult invalid() {
			return new RotationResult(RotationStatus.INVALID, null, null);
		}

		static RotationResult inactive() {
			return new RotationResult(RotationStatus.INACTIVE, null, null);
		}
	}
}
