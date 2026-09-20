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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import io.edupilot.user.User;

@Service
public class RefreshTokenService {

	private static final int TOKEN_BYTES = 32;
	private static final Duration ACTIVITY_WRITE_THROTTLE = Duration.ofMinutes(5);

	private final RefreshTokenRepository refreshTokenRepository;
	private final AuthSessionRepository authSessionRepository;
	private final AuthSessionProperties sessionProperties;
	private final Clock clock;
	private final SecureRandom secureRandom;
	private final Cache<Long, Boolean> recentlyTouchedSessions;

	@Autowired
	public RefreshTokenService(
		RefreshTokenRepository refreshTokenRepository,
		AuthSessionRepository authSessionRepository,
		AuthSessionProperties sessionProperties,
		Clock clock
	) {
		this(
			refreshTokenRepository,
			authSessionRepository,
			sessionProperties,
			clock,
			Ticker.systemTicker()
		);
	}

	RefreshTokenService(
		RefreshTokenRepository refreshTokenRepository,
		AuthSessionRepository authSessionRepository,
		AuthSessionProperties sessionProperties,
		Clock clock,
		Ticker ticker
	) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.authSessionRepository = authSessionRepository;
		this.sessionProperties = sessionProperties;
		this.clock = clock;
		this.secureRandom = new SecureRandom();
		this.recentlyTouchedSessions = Caffeine.newBuilder()
			.expireAfterWrite(ACTIVITY_WRITE_THROTTLE)
			.ticker(ticker)
			.build();
	}

	@Transactional
	public IssueResult issue(User user) {
		Instant now = clock.instant();
		Duration idleTtl = sessionProperties.idleTtl(user.getRole());
		AuthSession session = authSessionRepository.saveAndFlush(
			AuthSession.create(user, now, idleTtl, sessionProperties.absoluteTtl())
		);
		rememberTouch(session);

		String rawToken = generateToken();
		refreshTokenRepository.save(new RefreshToken(
			user,
			session,
			hash(rawToken),
			session.getAbsoluteExpiresAt()
		));
		return new IssueResult(rawToken, session, idleTtl);
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
			revokeAll(user.getId(), now);
			return RotationResult.inactive();
		}
		if (token.isRevoked()) {
			revokeReusedTokenFamily(token, now);
			return RotationResult.invalid();
		}
		if (token.getAuthSession() == null && !token.getExpiresAt().isAfter(now)) {
			token.revoke(now);
			return RotationResult.invalid();
		}

		Duration idleTtl = sessionProperties.idleTtl(user.getRole());
		AuthSession session = resolveSession(token, now, idleTtl);
		SessionStatus sessionStatus = validateSession(token, session, now);
		if (sessionStatus != SessionStatus.SUCCESS) {
			return RotationResult.failure(sessionStatus);
		}

		token.revoke(now);
		touchIfDue(session, now, idleTtl);
		String newRawToken = generateToken();
		refreshTokenRepository.save(new RefreshToken(
			user,
			session,
			hash(newRawToken),
			session.getAbsoluteExpiresAt()
		));
		return RotationResult.success(user, newRawToken, session, idleTtl);
	}

	@Transactional
	public ActivityResult recordActivity(Long userId, String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			return ActivityResult.invalid();
		}

		Instant now = clock.instant();
		RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
			.orElse(null);
		if (token == null || !token.getUser().getId().equals(userId)) {
			return ActivityResult.invalid();
		}

		User user = token.getUser();
		if (!user.isActive()) {
			revokeAll(user.getId(), now);
			return ActivityResult.inactive();
		}
		if (token.isRevoked()) {
			revokeReusedTokenFamily(token, now);
			return ActivityResult.invalid();
		}
		if (token.getAuthSession() == null && !token.getExpiresAt().isAfter(now)) {
			token.revoke(now);
			return ActivityResult.invalid();
		}

		Duration idleTtl = sessionProperties.idleTtl(user.getRole());
		AuthSession session = resolveSession(token, now, idleTtl);
		SessionStatus sessionStatus = validateSession(token, session, now);
		if (sessionStatus != SessionStatus.SUCCESS) {
			return ActivityResult.failure(sessionStatus);
		}

		touchIfDue(session, now, idleTtl);
		return ActivityResult.success(session, idleTtl);
	}

	@Transactional
	public void logout(String rawToken) {
		if (!StringUtils.hasText(rawToken)) {
			return;
		}
		Instant now = clock.instant();
		refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
			.ifPresent(token -> {
				AuthSession linkedSession = token.getAuthSession();
				if (linkedSession == null) {
					token.revoke(now);
					return;
				}
				authSessionRepository.findByIdForUpdate(linkedSession.getId())
					.ifPresent(session -> revokeSession(session, now));
			});
	}

	@Transactional
	public void revokeAll(Long userId) {
		revokeAll(userId, clock.instant());
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

	private AuthSession resolveSession(
		RefreshToken token,
		Instant now,
		Duration idleTtl
	) {
		AuthSession linkedSession = token.getAuthSession();
		if (linkedSession != null) {
			return authSessionRepository.findByIdForUpdate(linkedSession.getId()).orElse(null);
		}

		// V41 이전 활성 refresh는 최초 사용 시 기존 만료 시각을 절대 만료로 채택한다.
		AuthSession adoptedSession = authSessionRepository.saveAndFlush(
			AuthSession.adoptLegacy(token.getUser(), now, idleTtl, token.getExpiresAt())
		);
		token.attachSession(adoptedSession);
		refreshTokenRepository.save(token);
		rememberTouch(adoptedSession);
		return adoptedSession;
	}

	private void touchIfDue(AuthSession session, Instant now, Duration idleTtl) {
		Long sessionId = session.getId();
		if (sessionId != null && recentlyTouchedSessions.getIfPresent(sessionId) != null) {
			return;
		}
		session.touch(now, idleTtl);
		authSessionRepository.saveAndFlush(session);
		rememberTouch(session);
	}

	private SessionStatus validateSession(
		RefreshToken token,
		AuthSession session,
		Instant now
	) {
		if (session == null || session.isRevoked()) {
			return SessionStatus.INVALID;
		}
		if (session.isAbsoluteExpired(now)) {
			revokeSession(session, now);
			return SessionStatus.ABSOLUTE_EXPIRED;
		}
		if (session.isIdleExpired(now)) {
			revokeSession(session, now);
			return SessionStatus.IDLE_EXPIRED;
		}
		if (!token.getExpiresAt().isAfter(now)) {
			revokeSession(session, now);
			return SessionStatus.INVALID;
		}
		return SessionStatus.SUCCESS;
	}

	private void rememberTouch(AuthSession session) {
		Long sessionId = session.getId();
		if (sessionId == null) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			recentlyTouchedSessions.put(sessionId, Boolean.TRUE);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(
			new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					recentlyTouchedSessions.put(sessionId, Boolean.TRUE);
				}
			}
		);
	}

	private void revokeReusedTokenFamily(RefreshToken token, Instant now) {
		AuthSession linkedSession = token.getAuthSession();
		if (linkedSession == null) {
			// 레거시 토큰은 세션 계보를 복원할 수 없어 기존 사용자 단위 폐기를 유지한다.
			revokeAll(token.getUser().getId(), now);
			return;
		}
		authSessionRepository.findByIdForUpdate(linkedSession.getId())
			.ifPresent(session -> revokeSession(session, now));
	}

	private void revokeSession(AuthSession session, Instant now) {
		session.revoke(now);
		if (session.getId() != null) {
			refreshTokenRepository.revokeAllActiveBySessionId(session.getId(), now);
			recentlyTouchedSessions.invalidate(session.getId());
		}
	}

	private void revokeAll(Long userId, Instant now) {
		authSessionRepository.revokeAllActiveByUserId(userId, now);
		refreshTokenRepository.revokeAllActiveByUserId(userId, now);
	}

	private String generateToken() {
		byte[] token = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(token);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	public enum SessionStatus {
		SUCCESS,
		INVALID,
		INACTIVE,
		IDLE_EXPIRED,
		ABSOLUTE_EXPIRED
	}

	public record IssueResult(
		String rawToken,
		AuthSession session,
		Duration idleTtl
	) {
	}

	public record RotationResult(
		SessionStatus status,
		User user,
		String rawToken,
		AuthSession session,
		Duration idleTtl
	) {
		static RotationResult success(
			User user,
			String rawToken,
			AuthSession session,
			Duration idleTtl
		) {
			return new RotationResult(
				SessionStatus.SUCCESS,
				user,
				rawToken,
				session,
				idleTtl
			);
		}

		static RotationResult invalid() {
			return failure(SessionStatus.INVALID);
		}

		static RotationResult inactive() {
			return failure(SessionStatus.INACTIVE);
		}

		static RotationResult idleExpired() {
			return failure(SessionStatus.IDLE_EXPIRED);
		}

		static RotationResult absoluteExpired() {
			return failure(SessionStatus.ABSOLUTE_EXPIRED);
		}

		static RotationResult failure(SessionStatus status) {
			return new RotationResult(status, null, null, null, null);
		}
	}

	public record ActivityResult(
		SessionStatus status,
		AuthSession session,
		Duration idleTtl
	) {
		static ActivityResult success(AuthSession session, Duration idleTtl) {
			return new ActivityResult(SessionStatus.SUCCESS, session, idleTtl);
		}

		static ActivityResult invalid() {
			return failure(SessionStatus.INVALID);
		}

		static ActivityResult inactive() {
			return failure(SessionStatus.INACTIVE);
		}

		static ActivityResult idleExpired() {
			return failure(SessionStatus.IDLE_EXPIRED);
		}

		static ActivityResult absoluteExpired() {
			return failure(SessionStatus.ABSOLUTE_EXPIRED);
		}

		static ActivityResult failure(SessionStatus status) {
			return new ActivityResult(status, null, null);
		}
	}
}
