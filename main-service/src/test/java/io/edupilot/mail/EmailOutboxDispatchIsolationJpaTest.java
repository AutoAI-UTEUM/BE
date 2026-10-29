package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:mail-dispatch-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/mail-dispatch-isolation",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.outbox.recovery-delay-ms=3600000",
	"edupilot.mail.outbox.dispatch.mode=ISOLATED_TRIAL",
	"edupilot.mail.outbox.dispatch.delivery-ids=1,2,3",
	"edupilot.mail.outbox.dispatch.recipient=trial@example.test"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailOutboxDispatchIsolationJpaTest {
	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url = System.getenv("DISPATCH_MYSQL_URL");
		if (url == null || url.isBlank()) { return; }
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/outbox_dispatch_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Dispatch tests require the disposable loopback database");
		}
		settings.add("spring.datasource.url", () -> url);
		settings.add("spring.datasource.username", () -> "root");
		settings.add("spring.datasource.password", () -> "");
		settings.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
	}
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailDeliveryStore history;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@Autowired private MailProperties properties;
	@Autowired private Clock clock;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private EmailSender sender;

	@BeforeEach void setup() {
		if (System.getenv("DISPATCH_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("outbox_dispatch_synthetic");
		}
		jobs.deleteAll();
		deliveries.deleteAll();
		if (!quotaLocks.existsById(1)) {
			quotaLocks.saveAndFlush(EmailQuotaLock.initial());
		}
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("synthetic-receipt"));
	}

	@Test void recoveryAndDirectKicksSendOnlyTheThreeApprovedDeliveries() {
		queue(1, "trial@example.test", EmailDeliveryType.EMAIL_VERIFY);
		queue(2, "trial@example.test", EmailDeliveryType.PASSWORD_RESET);
		queue(3, "trial@example.test", EmailDeliveryType.NOTIFICATION);
		queue(4, "trial@example.test", EmailDeliveryType.EMAIL_VERIFY);
		queue(5, "unapproved@example.test", EmailDeliveryType.PASSWORD_RESET);

		worker().recoverPending();
		worker().kick(4L);
		worker().kick(5L);
		worker().recoverPending();

		verify(sender, times(3)).send(any());
		for (long id = 1; id <= 3; id++) {
			assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.SENT);
		}
		for (long id = 4; id <= 5; id++) {
			assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.READY);
			assertThat(deliveries.findById(id).orElseThrow().getAttemptCount()).isZero();
		}
	}

	@Test void selectedIdsAlsoRequireMatchingHistoryAndEncryptedPayloadRecipients() {
		queue(1, "trial@example.test", EmailDeliveryType.EMAIL_VERIFY);
		queue(2, "unapproved@example.test", EmailDeliveryType.PASSWORD_RESET);
		queue(3, "trial@example.test", "unapproved@example.test", EmailDeliveryType.NOTIFICATION);
		worker().recoverPending();
		worker().kick(2L);
		worker().kick(3L);
		verify(sender, times(1)).send(any());
		for (long id = 2; id <= 3; id++) {
			assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.READY);
			assertThat(deliveries.findById(id).orElseThrow().getAttemptCount()).isZero();
		}
	}

	@Test void definiteThrottleConsumesTheSingleAttemptAcrossFreshWorkers() {
		queue(1, "trial@example.test", EmailDeliveryType.PASSWORD_RESET);
		when(sender.send(any())).thenThrow(new EmailSendRejection(true));
		worker().kick(1L);
		assertThat(jobs.findById(1L).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.RETRY);
		jdbc.update("update email_outbox set next_attempt_at='2000-01-01 00:00:00' where delivery_id=1");
		worker().recoverPending();
		worker().kick(1L);
		assertThat(jobs.findById(1L).orElseThrow().getAttemptCount()).isEqualTo(1);
		assertThat(deliveries.findById(1L).orElseThrow().getAttemptCount()).isEqualTo(1);
		verify(sender, times(1)).send(any());
		// The held retry still follows normal expiry/payload cleanup; it is never labelled SENT.
		jdbc.update("update email_outbox set expires_at='2000-01-01 00:00:00' where delivery_id=1");
		worker().recoverPending();
		assertThat(jobs.findById(1L).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(jobs.findById(1L).orElseThrow().getLastErrorCode()).isEqualTo("PAYLOAD_EXPIRED");
		assertThat(jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id=1", byte[].class)).isNull();
	}

	@Test void parallelWorkersCannotSpendAnApprovedIdMoreThanOnce() throws Exception {
		queue(1, "trial@example.test", EmailDeliveryType.EMAIL_VERIFY);
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		when(sender.send(any())).thenAnswer(invocation -> {
			sending.countDown();
			assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
			return new EmailDeliveryResult("synthetic-receipt");
		});
		try (var threads = Executors.newFixedThreadPool(8)) {
			var first = threads.submit(() -> worker().kick(1L));
			assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
			for (int index = 0; index < 12; index++) {
				threads.submit(() -> worker().kick(1L)).get(10, TimeUnit.SECONDS);
			}
			finish.countDown();
			first.get(10, TimeUnit.SECONDS);
		} finally { finish.countDown(); }
		worker().recoverPending();
		assertThat(jobs.findById(1L).orElseThrow().getAttemptCount()).isEqualTo(1);
		verify(sender, times(1)).send(any());
	}

	@Test void finalSendingBoundaryRejectsAClaimWithAnUnapprovedPayloadRecipient() {
		queue(1, "trial@example.test", EmailDeliveryType.PASSWORD_RESET);
		var claim = outbox.claim(1L);
		var wrong = new EmailOutboxStore.Claim(claim.id(), claim.token(),
			new EmailMessage("unapproved@example.test", "Synthetic", "Synthetic body", null,
				EmailDeliveryType.PASSWORD_RESET));
		assertThat(outbox.beginSending(wrong)).isFalse();
		assertThat(jobs.findById(1L).orElseThrow().getAttemptCount()).isZero();
		verify(sender, never()).send(any());
	}

	@Test void previouslyAttemptedHistoryCannotBeReapprovedByResettingOnlyTheJobCounter() {
		queue(1, "trial@example.test", EmailDeliveryType.PASSWORD_RESET);
		jdbc.update("update email_deliveries set attempt_count=1 where id=1");
		worker().recoverPending();
		worker().kick(1L);
		assertThat(outbox.claim(1L)).isNull();
		assertThat(jobs.findById(1L).orElseThrow().getAttemptCount()).isZero();
		verify(sender, never()).send(any());
	}

	private void queue(long id, String recipient, EmailDeliveryType type) {
		queue(id, recipient, recipient, type);
	}

	private void queue(long id, String historyRecipient, String payloadRecipient, EmailDeliveryType type) {
		jdbc.update("insert into email_deliveries(id,recipient,type,status,subject,created_at,attempt_count) "
			+ "values(?,?,?,'QUEUED','Synthetic',?,0)", id, historyRecipient, type.name(), Timestamp.from(clock.instant()));
		outbox.enqueue(id, new EmailMessage(payloadRecipient, "Synthetic", "Synthetic body", null, type),
			clock.instant().plusSeconds(1800));
	}

	private EmailOutboxWorker worker() {
		return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run);
	}
}
