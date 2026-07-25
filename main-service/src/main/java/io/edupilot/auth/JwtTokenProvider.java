package io.edupilot.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtTokenProvider {

	private static final String ROLE_CLAIM = "role";

	private final JwtProperties properties;
	private final Clock clock;
	private final SecretKey secretKey;

	public JwtTokenProvider(JwtProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		byte[] secret = decodeSecret(properties.secret());
		if (secret.length < 32) {
			throw new IllegalArgumentException(
				"EDUPILOT_JWT_SECRET must decode to at least 256 bits."
			);
		}
		this.secretKey = Keys.hmacShaKeyFor(secret);
	}

	public String createAccessToken(User user) {
		Instant issuedAt = clock.instant();
		Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());

		return Jwts.builder()
			.subject(user.getId().toString())
			.claim(ROLE_CLAIM, user.getRole().name())
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(expiresAt))
			.signWith(secretKey)
			.compact();
	}

	public AuthenticatedUser parseAccessToken(String token) {
		try {
			Claims claims = Jwts.parser()
				.verifyWith(secretKey)
				.build()
				.parseSignedClaims(token)
				.getPayload();
			Long userId = Long.valueOf(claims.getSubject());
			UserRole role = UserRole.valueOf(claims.get(ROLE_CLAIM, String.class));
			return new AuthenticatedUser(userId, role);
		} catch (ExpiredJwtException exception) {
			throw new JwtTokenValidationException(ErrorCode.TOKEN_EXPIRED, exception);
		} catch (JwtException | IllegalArgumentException exception) {
			throw new JwtTokenValidationException(ErrorCode.TOKEN_INVALID, exception);
		}
	}

	public long accessTokenExpiresInSeconds() {
		return properties.accessTokenTtl().toSeconds();
	}

	private byte[] decodeSecret(String encodedSecret) {
		try {
			return Decoders.BASE64.decode(encodedSecret);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
				"EDUPILOT_JWT_SECRET must be a valid Base64 value.",
				exception
			);
		}
	}
}
