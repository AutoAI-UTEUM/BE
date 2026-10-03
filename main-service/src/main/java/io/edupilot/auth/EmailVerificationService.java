package io.edupilot.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.auth.dto.EmailVerificationResponse;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.mail.EmailService;
import io.edupilot.mail.EmailTemplates;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class EmailVerificationService {
	private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");
	private final UserRepository users;
	private final EmailVerificationTokenRepository tokens;
	private final EmailVerificationRateLimiter limits;
	private final EmailService mail;
	private final EmailTemplates templates;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();
	public EmailVerificationService(UserRepository users, EmailVerificationTokenRepository tokens,
		EmailVerificationRateLimiter limits, EmailService mail, EmailTemplates templates, Clock clock) {
		this.users = users; this.tokens = tokens; this.limits = limits;
		this.mail = mail; this.templates = templates; this.clock = clock;
	}
	@Transactional
	public void signup(User user, String ip) {
		user.beginEmailVerification();
		issue(user);
	}
	@Transactional
	public void request(Long userId, String ip) {
		User user = users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
		assertActive(user);
		if (user.isEmailVerified()) { return; }
		if (!limits.allowRequest(userId, ip)) { throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED); }
		user.beginEmailVerification();
		issue(user);
	}
	@Transactional(readOnly = true)
	public EmailVerificationResponse status(Long userId) {
		User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_INACTIVE));
		assertActive(user);
		return EmailVerificationResponse.from(user);
	}
	@Transactional
	public EmailVerificationResponse confirm(String raw, String ip) {
		if (!limits.allowConfirm(ip)) { throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED); }
		if (raw == null || !FORMAT.matcher(raw).matches()) { throw invalid(); }
		String tokenHash = hash(raw);
		Long userId = tokens.findUserIdByTokenHash(tokenHash).orElseThrow(this::invalid);
		// Same lock order as resend/withdraw: user first, then token.
		User user = users.findByIdForUpdate(userId).orElseThrow(this::invalid);
		EmailVerificationToken token = tokens.findByTokenHashForUpdate(tokenHash).orElseThrow(this::invalid);
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (!user.isActive() || user.isEmailVerified() || !token.isUsable(now, hash(user.getEmail()))) { throw invalid(); }
		token.use(now);
		user.verifyEmail(now);
		tokens.invalidateUnused(userId, now);
		return EmailVerificationResponse.from(user);
	}
	@Scheduled(cron = "0 23 * * * *")
	@Transactional
	public void deleteExpiredTokens() { tokens.deleteExpired(clock.instant()); }
	private void issue(User user) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant expiry = now.plus(Duration.ofMinutes(30));
		tokens.invalidateUnused(user.getId(), now);
		byte[] entropy = new byte[32]; random.nextBytes(entropy);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
		tokens.saveAndFlush(EmailVerificationToken.create(user, hash(raw), hash(user.getEmail()), now, expiry));
		mail.sendAsync(templates.emailVerify("/verify-email?token=" + raw).to(user.getEmail()), expiry);
	}
	static String hash(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
	}
	private void assertActive(User user) { if (!user.isActive()) { throw new BusinessException(ErrorCode.USER_INACTIVE); } }
	private BusinessException invalid() { return new BusinessException(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID); }
}
