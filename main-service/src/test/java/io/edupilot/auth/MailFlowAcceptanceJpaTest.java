package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.ai.AiClient;
import io.edupilot.deletion.DeletionJournalLock;
import io.edupilot.deletion.DeletionJournalLockRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryResult;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailDeliveryStore;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailOutboxRepository;
import io.edupilot.mail.EmailOutboxStatus;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailOutboxWorker;
import io.edupilot.mail.EmailPayloadCipher;
import io.edupilot.mail.EmailQuotaLock;
import io.edupilot.mail.EmailQuotaLockRepository;
import io.edupilot.mail.EmailSender;
import io.edupilot.mail.MailProperties;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserService;
import io.edupilot.user.UserStatus;

/** Synthetic acceptance complements the existing three-mail ISOLATED_TRIAL lifecycle test. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:mail-flow-acceptance-20261007;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-mail-acceptance",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/mail-flow-acceptance-20261007",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.base-url=https://mail-flow.example.test", "edupilot.mail.outbox.dispatch.mode=NORMAL",
	"edupilot.mail.outbox.recovery-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
@Import(MailFlowAcceptanceJpaTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailFlowAcceptanceJpaTest {
	private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
	private static final String PASSWORD = "syntheticPassword123";
	private static final String NEW_PASSWORD = "replacementPassword456";
	@Autowired private UserRepository users;
	@Autowired private UserService userService;
	@Autowired private PasswordEncoder passwords;
	@Autowired private EmailVerificationService verification;
	@Autowired private EmailVerificationTokenRepository verificationTokens;
	@Autowired private PasswordResetService resets;
	@Autowired private PasswordResetTokenRepository resetTokens;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailDeliveryStore history;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailPayloadCipher cipher;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@Autowired private DeletionJournalLockRepository deletionLocks;
	@Autowired private MailProperties properties;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private MailFlowAcceptanceClock time;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private EmailSender sender;
	@MockitoBean private AiClient ai;
	// Commit callbacks leave jobs durable until this test explicitly recovers them.
	@MockitoBean(name = "mailExecutor") private Executor lostExecutor;
	private final List<EmailMessage> messages = new ArrayList<>();
	private User account;
	private String recipient;
	private String ip;

	@BeforeEach
	void prepareSyntheticAccount() {
		time.set(NOW);
		jobs.deleteAll();
		deliveries.deleteAll();
		if (!quotaLocks.existsById(1)) quotaLocks.saveAndFlush(EmailQuotaLock.initial());
		if (!deletionLocks.existsById(1)) deletionLocks.saveAndFlush(DeletionJournalLock.initial());
		// Keep previous accounts to avoid deleting withdrawal evidence; mail fixtures are per-test.
		recipient = "mail-flow-" + users.count() + "@example.test";
		account = users.saveAndFlush(User.create(recipient, passwords.encode(PASSWORD), "Synthetic learner"));
		ip = "192.0.2." + account.getId();
		when(sender.send(any())).thenAnswer(invocation -> {
			messages.add(invocation.getArgument(0));
			return new EmailDeliveryResult("synthetic-mail-flow-receipt");
		});
	}

	@Test
	void resendBeforeRecoveryKeepsBothBodiesButOnlyTheNewestFragmentCanConfirm() {
		signup();
		Long firstId = lastJobId();
		EmailMessage first = queuedMessage(firstId);
		String firstToken = fragmentToken(first);
		time.set(NOW.plusSeconds(60));
		verification.request(account.getId(), ip);
		Long secondId = lastJobId();
		EmailMessage second = queuedMessage(secondId);
		String secondToken = fragmentToken(second);

		assertThat(secondToken).isNotEqualTo(firstToken);
		assertThat(payloadExpiry(firstId)).isEqualTo(NOW.plusSeconds(1800));
		assertThat(payloadExpiry(secondId)).isEqualTo(NOW.plusSeconds(1860));
		assertThat(verificationTokens.findAll()).filteredOn(token -> token.getTokenHash().equals(EmailVerificationService.hash(firstToken)))
			.singleElement().satisfies(token -> assertThat(token.getUsedAt()).isEqualTo(NOW.plusSeconds(60)));
		assertVerificationInvalid(firstToken);
		assertThat(verification.status(account.getId()).emailVerificationRequired()).isTrue();
		assertThat(messages).isEmpty();

		worker().recoverPending();
		worker().recoverPending();

		assertThat(messages).containsExactly(first, second);
		assertVerificationInvalid(firstToken);
		assertThat(verification.confirm(secondToken, ip).emailVerificationRequired()).isFalse();
		assertVerificationInvalid(secondToken);
		verification.request(account.getId(), ip);
		assertThat(jobs.count()).isEqualTo(2);
		assertSentAndErased(firstId, secondId);
		verifyNoInteractions(ai);
	}

	@Test
	void fragmentTokenAndQueuedPayloadExpireTogetherAtTheExactThirtyMinuteBoundary() {
		signup();
		Long id = lastJobId();
		String token = fragmentToken(queuedMessage(id));
		Instant expiry = verificationTokens.findAll().stream()
			.filter(stored -> stored.getTokenHash().equals(EmailVerificationService.hash(token)))
			.findFirst().orElseThrow().getExpiresAt();
		assertThat(payloadExpiry(id)).isEqualTo(expiry);
		time.set(expiry);

		assertVerificationInvalid(token);
		worker().recoverPending();
		worker().kick(id);

		assertExpiredWithoutAttempt(id);
		assertThat(verification.status(account.getId()).emailVerificationRequired()).isTrue();
		assertThat(messages).isEmpty();
	}

	@Test
	void payloadClaimedBeforeExpiryCannotBeginSendingAtTheExpiryBoundary() {
		signup();
		Long id = lastJobId();
		Instant expiry = payloadExpiry(id);
		time.set(expiry.minusSeconds(1));
		var claim = outbox.claim(id);
		assertThat(claim).isNotNull();
		time.set(expiry);

		assertThat(outbox.beginSending(claim)).isFalse();
		assertExpiredWithoutAttempt(id);
		assertThat(messages).isEmpty();
	}

	@Test
	void unexpiredLegacyQueryPayloadIsRecoveredUnchangedAndDoesNotVerifyTheAccount() {
		String legacyUrl = "https://mail-flow.example.test/verify-email?token=" + "L".repeat(43);
		EmailMessage legacy = new EmailMessage(recipient, "Synthetic legacy verification", legacyUrl,
			"<a href=\"" + legacyUrl + "\">Synthetic old link</a>", EmailDeliveryType.EMAIL_VERIFY);
		Long id = history.queue(legacy);
		outbox.enqueue(id, legacy, NOW.plusSeconds(1800));

		worker().recoverPending();

		assertThat(messages).containsExactly(legacy);
		assertThat(link(messages.getFirst()).getRawQuery()).startsWith("token=");
		assertThat(link(messages.getFirst()).getRawFragment()).isNull();
		assertThat(verification.status(account.getId()).emailVerificationRequired()).isTrue();
		assertSentAndErased(id);
	}

	@Test
	void expiredLegacyPayloadIsErasedWithoutConsumingARecipientReservation() {
		EmailMessage legacy = new EmailMessage(recipient, "Synthetic expired legacy verification",
			"https://mail-flow.example.test/verify-email?token=" + "E".repeat(43), null, EmailDeliveryType.EMAIL_VERIFY);
		Long id = history.queue(legacy);
		outbox.enqueue(id, legacy, NOW);

		worker().recoverPending();
		worker().kick(id);

		assertExpiredWithoutAttempt(id);
		assertThat(jdbc.queryForObject("select count(*) from email_send_reservations where delivery_id=?", Long.class, id))
			.isZero();
		assertThat(messages).isEmpty();
	}

	@Test
	void resetReissueInvalidatesTheOlderLinkAndSuccessfulResetDoesNotVerifyEmail() {
		signup();
		Long verificationId = lastJobId();
		String verificationToken = fragmentToken(queuedMessage(verificationId));
		resets.request(recipient, ip);
		Long firstResetId = lastJobId();
		String firstResetToken = resetToken(queuedMessage(firstResetId));
		time.set(NOW.plusSeconds(60));
		resets.request(recipient, ip);
		Long secondResetId = lastJobId();
		String secondResetToken = resetToken(queuedMessage(secondResetId));

		assertThat(secondResetToken).isNotEqualTo(firstResetToken);
		assertResetInvalid(firstResetToken);
		worker().recoverPending();
		assertThat(messages).extracting(EmailMessage::type).containsExactly(
			EmailDeliveryType.EMAIL_VERIFY, EmailDeliveryType.PASSWORD_RESET, EmailDeliveryType.PASSWORD_RESET);
		resets.confirm(secondResetToken, NEW_PASSWORD, ip);
		assertResetInvalid(secondResetToken);
		assertThat(passwords.matches(NEW_PASSWORD, users.findById(account.getId()).orElseThrow().getPasswordHash())).isTrue();
		assertThat(verification.status(account.getId()).emailVerificationRequired()).isTrue();
		assertThat(verification.confirm(verificationToken, ip).emailVerificationRequired()).isFalse();
		assertSentAndErased(verificationId, firstResetId, secondResetId);
		verifyNoInteractions(ai);
	}

	@Test
	void resetTokenAndPayloadRejectTheExactBoundaryWhenQueueingHasNoDelay() {
		resets.request(recipient, ip);
		Long id = lastJobId();
		String raw = resetToken(queuedMessage(id));
		Instant expiry = resetTokens.findByTokenHash(PasswordResetService.hash(raw)).orElseThrow().getExpiresAt();
		assertThat(payloadExpiry(id)).isEqualTo(expiry);
		time.set(expiry);

		assertResetInvalid(raw);
		worker().recoverPending();

		assertExpiredWithoutAttempt(id);
		assertThat(messages).isEmpty();
		assertThat(passwords.matches(PASSWORD, users.findById(account.getId()).orElseThrow().getPasswordHash())).isTrue();
	}

	@Test
	void withdrawalLeavesCompletionMailForTheOriginalRecipientAndRejectsBothOutstandingTokens() {
		signup();
		Long verificationId = lastJobId();
		String verificationToken = fragmentToken(queuedMessage(verificationId));
		resets.request(recipient, ip);
		Long resetId = lastJobId();
		String rawReset = resetToken(queuedMessage(resetId));
		userService.withdraw(account.getId(), PASSWORD);
		Long withdrawalId = lastJobId();

		assertVerificationInvalid(verificationToken);
		assertResetInvalid(rawReset);
		assertThat(users.findById(account.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DELETED);
		assertThat(users.findById(account.getId()).orElseThrow().getEmail()).isNotEqualTo(recipient);
		assertThat(payloadExpiry(withdrawalId)).isEqualTo(NOW.plus(Duration.ofHours(24)));
		worker().recoverPending();

		assertThat(messages).hasSize(3).allSatisfy(message -> assertThat(message.to()).isEqualTo(recipient));
		EmailMessage completed = messages.getLast();
		assertThat(completed.type()).isEqualTo(EmailDeliveryType.NOTIFICATION);
		assertThat(completed.subject()).isEqualTo("[UTEUM] 회원 탈퇴 완료");
		assertThat(completed.textBody()).doesNotContain(verificationToken, rawReset);
		assertSentAndErased(verificationId, resetId, withdrawalId);
		verifyNoInteractions(ai);
	}

	private void signup() {
		new TransactionTemplate(transactions).executeWithoutResult(status ->
			verification.signup(users.findById(account.getId()).orElseThrow(), ip));
	}

	private Long lastJobId() {
		return jobs.findAll().stream().map(job -> job.getId()).max(Long::compareTo).orElseThrow();
	}

	private EmailMessage queuedMessage(Long id) {
		return cipher.decrypt(id, payload(id));
	}

	private byte[] payload(Long id) {
		return jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id=?", byte[].class, id);
	}

	private Instant payloadExpiry(Long id) {
		return jdbc.queryForObject("select expires_at from email_outbox where delivery_id=?", java.sql.Timestamp.class, id).toInstant();
	}

	private URI link(EmailMessage message) {
		return message.textBody().lines().filter(line -> line.startsWith("https://")).map(URI::create).findFirst().orElseThrow();
	}

	private String fragmentToken(EmailMessage message) {
		URI uri = link(message);
		assertThat(uri.getPath()).isEqualTo("/verify-email");
		assertThat(uri.getRawQuery()).isNull();
		assertThat(uri.getRawFragment()).matches("token=[A-Za-z0-9_-]{43}");
		assertThat(message.htmlBody()).contains("href=\"" + uri + "\"");
		return uri.getRawFragment().substring("token=".length());
	}

	private String resetToken(EmailMessage message) {
		URI uri = link(message);
		assertThat(uri.getPath()).isEqualTo("/reset-password");
		assertThat(uri.getRawQuery()).matches("token=[A-Za-z0-9_-]{43}");
		assertThat(uri.getRawFragment()).isNull();
		return uri.getRawQuery().substring("token=".length());
	}

	private void assertVerificationInvalid(String raw) {
		assertThatThrownBy(() -> verification.confirm(raw, ip)).isInstanceOfSatisfying(BusinessException.class,
			failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID));
	}

	private void assertResetInvalid(String raw) {
		assertThatThrownBy(() -> resets.confirm(raw, NEW_PASSWORD, ip)).isInstanceOfSatisfying(BusinessException.class,
			failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESET_TOKEN_INVALID));
	}

	private void assertSentAndErased(Long... ids) {
		for (Long id : ids) {
			assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.SENT);
			assertThat(jobs.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
			assertThat(deliveries.findById(id).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
			assertThat(payload(id)).isNull();
		}
	}

	private void assertExpiredWithoutAttempt(Long id) {
		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(jobs.findById(id).orElseThrow().getLastErrorCode()).isEqualTo("PAYLOAD_EXPIRED");
		assertThat(jobs.findById(id).orElseThrow().getAttemptCount()).isZero();
		assertThat(payload(id)).isNull();
	}

	private EmailOutboxWorker worker() {
		return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeConfiguration {
		@Bean @Primary MailFlowAcceptanceClock mailFlowAcceptanceClock() { return new MailFlowAcceptanceClock(); }
	}

	static final class MailFlowAcceptanceClock extends Clock {
		private volatile Instant instant = NOW;
		void set(Instant value) { instant = value; }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
		@Override public Instant instant() { return instant; }
	}
}
