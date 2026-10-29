package io.edupilot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.ai.AiClient;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryResult;
import io.edupilot.mail.EmailDeliveryStore;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailOutboxRepository;
import io.edupilot.mail.EmailOutboxStatus;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailOutboxWorker;
import io.edupilot.mail.EmailQuotaLock;
import io.edupilot.mail.EmailQuotaLockRepository;
import io.edupilot.mail.EmailSender;
import io.edupilot.mail.MailProperties;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:fragment-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173", "edupilot.ai.base-url=http://localhost:8000",
	"edupilot.ai.internal-token=synthetic-internal-token", "edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/fragment-outbox", "edupilot.mail.enabled=true",
	"edupilot.mail.provider=logging", "edupilot.mail.base-url=https://dev.uteum.com",
	"edupilot.mail.outbox.recovery-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
class EmailVerificationFragmentOutboxJpaTest {
	@Autowired private UserRepository users;
	@Autowired private EmailVerificationService verification;
	@Autowired private EmailVerificationTokenRepository tokens;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailDeliveryStore history;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@Autowired private MailProperties properties;
	@Autowired private Clock clock;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailSender sender;
	@MockitoBean(name = "mailExecutor") private Executor lostExecutor;

	@BeforeEach
	void setup() {
		jobs.deleteAll(); deliveries.deleteAll(); tokens.deleteAll(); users.deleteAll();
		if (!quotaLocks.existsById(1)) quotaLocks.saveAndFlush(EmailQuotaLock.initial());
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("synthetic-fragment-receipt"));
	}

	@Test
	void newWorkerRecoversTheIssuedFragmentAndConfirmationStillConsumesItOnce() {
		User account = users.saveAndFlush(User.create("fragment-synthetic@example.test", "!synthetic", "Synthetic learner"));
		verification.request(account.getId(), "192.0.2.1");
		Long id = jobs.findAll().getFirst().getId();
		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.READY);
		byte[] encrypted = payload(id);
		assertThat(new String(encrypted, StandardCharsets.ISO_8859_1))
			.doesNotContain("verify-email", "fragment-synthetic@example.test");
		verify(sender, never()).send(any());

		worker().recoverPending();
		worker().recoverPending();
		var delivered = ArgumentCaptor.forClass(EmailMessage.class);
		verify(sender, times(1)).send(delivered.capture());
		URI link = delivered.getValue().textBody().lines().filter(line -> line.startsWith("https://"))
			.map(URI::create).findFirst().orElseThrow();
		assertThat(link.getPath()).isEqualTo("/verify-email");
		assertThat(link.getRawQuery()).isNull();
		assertThat(link.getRawFragment()).matches("token=[A-Za-z0-9_-]{43}");
		assertThat(delivered.getValue().htmlBody()).contains("href=\"" + link + "\"");
		String raw = link.getRawFragment().substring("token=".length());
		assertThat(tokens.findAll()).extracting(EmailVerificationToken::getTokenHash)
			.contains(EmailVerificationService.hash(raw));
		assertThat(verification.confirm(raw, "192.0.2.2").emailVerificationRequired()).isFalse();
		assertThatThrownBy(() -> verification.confirm(raw, "192.0.2.2"))
			.isInstanceOfSatisfying(BusinessException.class,
				failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID));
		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.SENT);
		assertThat(payload(id)).isNull();
	}

	@Test
	void alreadyExpiredLegacyQueryPayloadCannotBeReplayedByRecovery() {
		EmailMessage old = new EmailMessage("expired-synthetic@example.test", "Synthetic old verification",
			"https://dev.uteum.com/verify-email?token=" + "A".repeat(43), null, EmailDeliveryType.EMAIL_VERIFY);
		Long id = history.queue(old);
		outbox.enqueue(id, old, clock.instant().minusSeconds(1));

		worker().recoverPending();
		worker().kick(id);

		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(jobs.findById(id).orElseThrow().getLastErrorCode()).isEqualTo("PAYLOAD_EXPIRED");
		assertThat(payload(id)).isNull();
		verify(sender, never()).send(any());
	}

	private byte[] payload(Long id) {
		return jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id = ?", byte[].class, id);
	}

	private EmailOutboxWorker worker() {
		return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run);
	}
}
