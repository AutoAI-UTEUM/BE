package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.auth.RefreshTokenService.SessionStatus;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");
	private static final Duration LEARNER_IDLE_TTL = Duration.ofHours(2);
	private static final Duration ABSOLUTE_TTL = Duration.ofDays(14);

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private AuthSessionRepository authSessionRepository;

	private final AtomicLong sessionIds = new AtomicLong(10L);
	private MutableClock clock;
	private MutableTicker ticker;
	private RefreshTokenService refreshTokenService;
	private User user;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(NOW);
		ticker = new MutableTicker();
		AuthSessionProperties properties = new AuthSessionProperties(
			ABSOLUTE_TTL,
			Duration.ofMinutes(30),
			Duration.ofHours(2),
			LEARNER_IDLE_TTL
		);
		refreshTokenService = new RefreshTokenService(
			refreshTokenRepository,
			authSessionRepository,
			properties,
			clock,
			ticker
		);
		user = User.create("user@example.com", "hash", "홍길동");
		ReflectionTestUtils.setField(user, "id", 1L);
		lenient().when(authSessionRepository.saveAndFlush(any(AuthSession.class)))
			.thenAnswer(invocation -> {
				AuthSession session = invocation.getArgument(0);
				if (session.getId() == null) {
					ReflectionTestUtils.setField(
						session,
						"id",
						sessionIds.getAndIncrement()
					);
				}
				return session;
			});
	}

	@Test
	void issueCreatesRoleBasedSessionAndStoresOnlyRefreshHash() {
		var result = refreshTokenService.issue(user);

		assertThat(result.idleTtl()).isEqualTo(LEARNER_IDLE_TTL);
		assertThat(result.session().getLastActivityAt()).isEqualTo(NOW);
		assertThat(result.session().getIdleExpiresAt()).isEqualTo(
			NOW.plus(LEARNER_IDLE_TTL)
		);
		assertThat(result.session().getAbsoluteExpiresAt()).isEqualTo(
			NOW.plus(ABSOLUTE_TTL)
		);

		ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(captor.capture());
		assertThat(captor.getValue().getAuthSession()).isSameAs(result.session());
		assertThat(captor.getValue().getExpiresAt()).isEqualTo(
			result.session().getAbsoluteExpiresAt()
		);
		assertThat(captor.getValue().getTokenHash())
			.isEqualTo(refreshTokenService.hash(result.rawToken()))
			.doesNotContain(result.rawToken());
	}

	@Test
	void issueUsesRoleBasedIdleTimeouts() {
		User admin = User.create("admin@example.com", "hash", "관리자", UserRole.ADMIN);
		ReflectionTestUtils.setField(admin, "id", 2L);

		var result = refreshTokenService.issue(admin);

		assertThat(result.idleTtl()).isEqualTo(Duration.ofMinutes(30));
		assertThat(result.session().getIdleExpiresAt()).isEqualTo(
			NOW.plus(Duration.ofMinutes(30))
		);

		User instructor = User.create(
			"instructor@example.com",
			"hash",
			"강사",
			UserRole.INSTRUCTOR
		);
		ReflectionTestUtils.setField(instructor, "id", 3L);
		var instructorResult = refreshTokenService.issue(instructor);
		assertThat(instructorResult.idleTtl()).isEqualTo(Duration.ofHours(2));
		assertThat(instructorResult.session().getIdleExpiresAt()).isEqualTo(
			NOW.plus(Duration.ofHours(2))
		);
	}

	@Test
	void rotateKeepsAbsoluteExpiryAndRevokesOnlyCurrentToken() {
		String oldRawToken = "old-refresh-token";
		AuthSession session = activeSession();
		RefreshToken oldToken = token(oldRawToken, session);
		stubLocked(oldRawToken, oldToken, session);

		var result = refreshTokenService.rotate(oldRawToken);

		assertThat(result.status()).isEqualTo(SessionStatus.SUCCESS);
		assertThat(result.rawToken()).isNotBlank().isNotEqualTo(oldRawToken);
		assertThat(result.session()).isSameAs(session);
		assertThat(oldToken.getRevokedAt()).isEqualTo(NOW);

		ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(captor.capture());
		assertThat(captor.getValue().getAuthSession()).isSameAs(session);
		assertThat(captor.getValue().getExpiresAt()).isEqualTo(
			session.getAbsoluteExpiresAt()
		);
		InOrder lockOrder = inOrder(refreshTokenRepository, authSessionRepository);
		lockOrder.verify(refreshTokenRepository).findByTokenHashForUpdate(
			refreshTokenService.hash(oldRawToken)
		);
		lockOrder.verify(authSessionRepository).findByIdForUpdate(session.getId());
	}

	@Test
	void repeatedRotationNeverMovesTheAbsoluteExpiry() {
		AuthSession session = activeSession();
		Instant absoluteExpiresAt = session.getAbsoluteExpiresAt();
		RefreshToken firstToken = token("first", session);
		stubLocked("first", firstToken, session);

		var firstRotation = refreshTokenService.rotate("first");
		RefreshToken secondToken = new RefreshToken(
			user,
			session,
			refreshTokenService.hash(firstRotation.rawToken()),
			absoluteExpiresAt
		);
		clock.advance(Duration.ofHours(1));
		ticker.advance(Duration.ofHours(1));
		stubLocked(firstRotation.rawToken(), secondToken, session);

		var secondRotation = refreshTokenService.rotate(firstRotation.rawToken());

		assertThat(secondRotation.status()).isEqualTo(SessionStatus.SUCCESS);
		assertThat(secondRotation.session()).isSameAs(session);
		assertThat(session.getAbsoluteExpiresAt()).isEqualTo(absoluteExpiresAt);
		ArgumentCaptor<RefreshToken> savedTokens = ArgumentCaptor.forClass(
			RefreshToken.class
		);
		verify(refreshTokenRepository, times(2)).save(savedTokens.capture());
		assertThat(savedTokens.getAllValues())
			.allSatisfy(token -> assertThat(token.getExpiresAt())
				.isEqualTo(absoluteExpiresAt));
	}

	@Test
	void activityWritesAtMostOncePerFiveMinutesAndNeverExtendsAbsoluteExpiry() {
		String rawToken = "activity-token";
		AuthSession session = activeSession();
		Instant absoluteExpiresAt = session.getAbsoluteExpiresAt();
		RefreshToken token = token(rawToken, session);
		stubLocked(rawToken, token, session);

		assertThat(refreshTokenService.recordActivity(1L, rawToken).status())
			.isEqualTo(SessionStatus.SUCCESS);
		clock.advance(Duration.ofMinutes(4));
		ticker.advance(Duration.ofMinutes(4));
		assertThat(refreshTokenService.recordActivity(1L, rawToken).status())
			.isEqualTo(SessionStatus.SUCCESS);
		verify(authSessionRepository, times(1)).saveAndFlush(session);

		clock.advance(Duration.ofMinutes(1));
		ticker.advance(Duration.ofMinutes(1));
		assertThat(refreshTokenService.recordActivity(1L, rawToken).status())
			.isEqualTo(SessionStatus.SUCCESS);
		verify(authSessionRepository, times(2)).saveAndFlush(session);
		assertThat(session.getIdleExpiresAt()).isEqualTo(
			NOW.plus(Duration.ofMinutes(5)).plus(LEARNER_IDLE_TTL)
		);
		assertThat(session.getAbsoluteExpiresAt()).isEqualTo(absoluteExpiresAt);
	}

	@Test
	void idleAndAbsoluteExpirationRevokeOnlyTheCurrentSessionFamily() {
		AuthSession idleExpired = AuthSession.create(
			user,
			NOW.minus(Duration.ofHours(3)),
			LEARNER_IDLE_TTL,
			ABSOLUTE_TTL
		);
		ReflectionTestUtils.setField(idleExpired, "id", 21L);
		RefreshToken idleToken = token("idle", idleExpired);
		stubLocked("idle", idleToken, idleExpired);

		assertThat(refreshTokenService.rotate("idle").status())
			.isEqualTo(SessionStatus.IDLE_EXPIRED);
		assertThat(idleExpired.getRevokedAt()).isEqualTo(NOW);
		verify(refreshTokenRepository).revokeAllActiveBySessionId(21L, NOW);

		AuthSession absoluteExpired = AuthSession.create(
			user,
			NOW.minus(Duration.ofDays(15)),
			LEARNER_IDLE_TTL,
			ABSOLUTE_TTL
		);
		ReflectionTestUtils.setField(absoluteExpired, "id", 22L);
		RefreshToken absoluteToken = token("absolute", absoluteExpired);
		stubLocked("absolute", absoluteToken, absoluteExpired);

		assertThat(refreshTokenService.rotate("absolute").status())
			.isEqualTo(SessionStatus.ABSOLUTE_EXPIRED);
		assertThat(absoluteExpired.getRevokedAt()).isEqualTo(NOW);
		verify(refreshTokenRepository).revokeAllActiveBySessionId(22L, NOW);
	}

	@Test
	void idleExpirationIsExclusiveBeforeBoundaryAndExpiredAtBoundary() {
		AuthSession justBeforeSession = activeSession();
		RefreshToken justBeforeToken = token("just-before", justBeforeSession);
		stubLocked("just-before", justBeforeToken, justBeforeSession);
		clock.advance(LEARNER_IDLE_TTL.minusNanos(1));
		ticker.advance(LEARNER_IDLE_TTL.minusNanos(1));

		assertThat(refreshTokenService.recordActivity(1L, "just-before").status())
			.isEqualTo(SessionStatus.SUCCESS);

		AuthSession atBoundarySession = AuthSession.create(
			user,
			NOW,
			LEARNER_IDLE_TTL,
			ABSOLUTE_TTL
		);
		ReflectionTestUtils.setField(atBoundarySession, "id", 23L);
		RefreshToken atBoundaryToken = token("at-boundary", atBoundarySession);
		stubLocked("at-boundary", atBoundaryToken, atBoundarySession);
		clock.advance(Duration.ofNanos(1));
		ticker.advance(Duration.ofNanos(1));

		assertThat(refreshTokenService.recordActivity(1L, "at-boundary").status())
			.isEqualTo(SessionStatus.IDLE_EXPIRED);
	}

	@Test
	void reuseDetectionIsSessionScopedForNewTokensAndUserScopedForLegacyTokens() {
		AuthSession session = activeSession();
		RefreshToken currentFamilyToken = token("current-family", session);
		currentFamilyToken.revoke(NOW.minusSeconds(1));
		stubLocked("current-family", currentFamilyToken, session);

		assertThat(refreshTokenService.rotate("current-family").status())
			.isEqualTo(SessionStatus.INVALID);
		verify(refreshTokenRepository).revokeAllActiveBySessionId(session.getId(), NOW);
		verify(refreshTokenRepository, never()).revokeAllActiveByUserId(1L, NOW);

		RefreshToken legacy = new RefreshToken(
			user,
			refreshTokenService.hash("legacy-reused"),
			NOW.plus(ABSOLUTE_TTL)
		);
		legacy.revoke(NOW.minusSeconds(1));
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash("legacy-reused")
		)).thenReturn(Optional.of(legacy));

		assertThat(refreshTokenService.rotate("legacy-reused").status())
			.isEqualTo(SessionStatus.INVALID);
		verify(refreshTokenRepository).revokeAllActiveByUserId(1L, NOW);
		verify(authSessionRepository).revokeAllActiveByUserId(1L, NOW);
	}

	@Test
	void activeLegacyTokenAdoptsSessionWithoutExtendingItsAbsoluteExpiry() {
		String rawToken = "legacy-active";
		Instant legacyExpiresAt = NOW.plus(Duration.ofDays(3));
		RefreshToken legacy = new RefreshToken(
			user,
			refreshTokenService.hash(rawToken),
			legacyExpiresAt
		);
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(rawToken)
		)).thenReturn(Optional.of(legacy));

		var result = refreshTokenService.rotate(rawToken);

		assertThat(result.status()).isEqualTo(SessionStatus.SUCCESS);
		assertThat(result.session().getAbsoluteExpiresAt()).isEqualTo(legacyExpiresAt);
		assertThat(legacy.getAuthSession()).isSameAs(result.session());
		verify(refreshTokenRepository).save(legacy);
	}

	@Test
	void logoutRevokesCurrentSessionFamilyAndMissingCookieIsIdempotent() {
		refreshTokenService.logout(null);
		verify(refreshTokenRepository, never()).findByTokenHashForUpdate(any());

		String rawToken = "logout";
		AuthSession session = activeSession();
		RefreshToken token = token(rawToken, session);
		stubLocked(rawToken, token, session);

		refreshTokenService.logout(rawToken);

		assertThat(session.getRevokedAt()).isEqualTo(NOW);
		verify(refreshTokenRepository).revokeAllActiveBySessionId(session.getId(), NOW);
		verify(refreshTokenRepository, never())
			.revokeAllActiveByUserId(eq(1L), any());
	}

	@Test
	void activityRejectsCookieOwnedByAnotherUserWithoutRevokingIt() {
		String rawToken = "other-users-cookie";
		AuthSession session = activeSession();
		RefreshToken token = token(rawToken, session);
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(rawToken)
		)).thenReturn(Optional.of(token));

		assertThat(refreshTokenService.recordActivity(999L, rawToken).status())
			.isEqualTo(SessionStatus.INVALID);
		assertThat(session.isRevoked()).isFalse();
		verify(refreshTokenRepository, never()).revokeAllActiveBySessionId(any(), any());
	}

	@Test
	void revokeAllRevokesEverySessionAndRefreshTokenForUser() {
		refreshTokenService.revokeAll(1L);

		verify(authSessionRepository).revokeAllActiveByUserId(1L, NOW);
		verify(refreshTokenRepository).revokeAllActiveByUserId(1L, NOW);
	}

	private AuthSession activeSession() {
		AuthSession session = AuthSession.create(
			user,
			NOW,
			LEARNER_IDLE_TTL,
			ABSOLUTE_TTL
		);
		ReflectionTestUtils.setField(session, "id", sessionIds.getAndIncrement());
		return session;
	}

	private RefreshToken token(String rawToken, AuthSession session) {
		return new RefreshToken(
			user,
			session,
			refreshTokenService.hash(rawToken),
			session.getAbsoluteExpiresAt()
		);
	}

	private void stubLocked(
		String rawToken,
		RefreshToken token,
		AuthSession session
	) {
		when(refreshTokenRepository.findByTokenHashForUpdate(
			refreshTokenService.hash(rawToken)
		)).thenReturn(Optional.of(token));
		when(authSessionRepository.findByIdForUpdate(session.getId()))
			.thenReturn(Optional.of(session));
	}

	private static final class MutableTicker implements Ticker {
		private long nanos;

		@Override
		public long read() {
			return nanos;
		}

		void advance(Duration duration) {
			nanos += duration.toNanos();
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}
	}
}
