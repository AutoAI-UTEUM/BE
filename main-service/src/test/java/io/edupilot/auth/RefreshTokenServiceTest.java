package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.auth.RefreshTokenService.RotationStatus;
import io.edupilot.user.User;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	private RefreshTokenService refreshTokenService;
	private User user;

	@BeforeEach
	void setUp() {
		JwtProperties properties = new JwtProperties(
			"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
			Duration.ofHours(1),
			Duration.ofDays(14)
		);
		refreshTokenService = new RefreshTokenService(
			refreshTokenRepository,
			properties,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		user = User.create("user@example.com", "hash", "홍길동");
		ReflectionTestUtils.setField(user, "id", 1L);
	}

	@Test
	void rotateRevokesCurrentAndStoresOnlyHashOfNewToken() {
		String oldRawToken = "old-refresh-token";
		RefreshToken oldToken = new RefreshToken(
			user,
			refreshTokenService.hash(oldRawToken),
			NOW.plusSeconds(60)
		);
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(oldRawToken)
		)).thenReturn(Optional.of(oldToken));

		var result = refreshTokenService.rotate(oldRawToken);

		assertThat(result.status()).isEqualTo(RotationStatus.SUCCESS);
		assertThat(result.rawToken()).isNotBlank().isNotEqualTo(oldRawToken);
		assertThat(oldToken.getRevokedAt()).isEqualTo(NOW);

		ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(captor.capture());
		assertThat(captor.getValue().getTokenHash())
			.isEqualTo(refreshTokenService.hash(result.rawToken()))
			.doesNotContain(result.rawToken());
	}

	@Test
	void reusingRevokedTokenRevokesEveryActiveTokenForUser() {
		String rawToken = "already-rotated";
		RefreshToken token = new RefreshToken(
			user,
			refreshTokenService.hash(rawToken),
			NOW.plusSeconds(60)
		);
		token.revoke(NOW.minusSeconds(1));
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(rawToken)
		)).thenReturn(Optional.of(token));

		var result = refreshTokenService.rotate(rawToken);

		assertThat(result.status()).isEqualTo(RotationStatus.INVALID);
		verify(refreshTokenRepository).revokeAllActiveByUserId(1L, NOW);
		verify(refreshTokenRepository, never()).save(any());
	}

	@Test
	void expiredAndUnknownTokensReturnInvalid() {
		String expiredRaw = "expired";
		RefreshToken expired = new RefreshToken(
			user,
			refreshTokenService.hash(expiredRaw),
			NOW
		);
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(expiredRaw)
		)).thenReturn(Optional.of(expired));
		assertThat(refreshTokenService.rotate(expiredRaw).status())
			.isEqualTo(RotationStatus.INVALID);
		assertThat(expired.getRevokedAt()).isEqualTo(NOW);

		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash("unknown")
		)).thenReturn(Optional.empty());
		assertThat(refreshTokenService.rotate("unknown").status())
			.isEqualTo(RotationStatus.INVALID);
	}

	@Test
	void logoutIsIdempotentWithMissingOrRevokedToken() {
		refreshTokenService.logout(null);
		verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());

		String rawToken = "revoked";
		RefreshToken revoked = new RefreshToken(
			user,
			refreshTokenService.hash(rawToken),
			NOW.plusSeconds(60)
		);
		revoked.revoke(NOW.minusSeconds(1));
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(rawToken)
		)).thenReturn(Optional.of(revoked));

		refreshTokenService.logout(rawToken);

		assertThat(revoked.getRevokedAt()).isEqualTo(NOW.minusSeconds(1));
		verify(refreshTokenRepository, never())
			.revokeAllActiveByUserId(eq(1L), any());
	}
}
