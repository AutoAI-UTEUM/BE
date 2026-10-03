package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:mail-outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/mail-outbox",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.outbox.recovery-delay-ms=3600000"
})
@ActiveProfiles("jpa-context")
class EmailOutboxJpaTest {
	@DynamicPropertySource
	static void optionalIsolatedMysql(DynamicPropertyRegistry settings) {
		String url = System.getenv("MAIL_MYSQL_URL");
		if (url == null || url.isBlank()) {
			return;
		}
		if (!url.matches("^jdbc:mysql://127\\.0\\.0\\.1:33316/mail_synthetic(?:\\?.*)?$")) {
			throw new IllegalArgumentException("Mail tests require the disposable loopback database");
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
	@Autowired private MailProperties properties;
	@Autowired private Clock clock;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private EmailSender sender;

	@BeforeEach void setup() {
		if (System.getenv("MAIL_MYSQL_URL") != null) {
			assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
			assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("mail_synthetic");
		}
		jobs.deleteAll();
		deliveries.deleteAll();
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("synthetic-receipt"));
	}

	@Test void lostInMemoryDispatchIsRecoveredByANewWorkerWithoutResendingCommittedMail() {
		var lostWorker = new EmailOutboxWorker(outbox, history, sender, properties, task -> { });
		Long id = new EmailService(history, outbox, lostWorker, properties, clock).sendAsync(message());
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.READY);
		byte[] persisted = jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id = ?", byte[].class, id);
		assertThat(new String(persisted, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("synthetic-token", "reset?token");
		worker().recoverPending();
		worker().recoverPending();
		worker().kick(id);
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.SENT);
		assertThat(deliveries.findById(id).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
		assertPayloadRemoved(id);
		verify(sender, times(1)).send(any());
	}

	@Test void simultaneousWorkersHaveOneProviderCallWithoutHoldingTheDbLockDuringTheCall() throws Exception {
		Long id = queued();
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		when(sender.send(any())).thenAnswer(invocation -> {
			sending.countDown();
			assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
			return new EmailDeliveryResult("synthetic-receipt");
		});
		try (var threads = Executors.newFixedThreadPool(2)) {
			var first = threads.submit(() -> worker().kick(id));
			assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
			var second = threads.submit(() -> worker().kick(id));
			second.get(10, TimeUnit.SECONDS); // The other worker can inspect SENDING and return.
			assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.SENDING);
			finish.countDown();
			first.get(10, TimeUnit.SECONDS);
		}
		verify(sender, times(1)).send(any());
	}

	@Test void expiredClaimBeforeProviderCallCanRecoverAndOldLeaseCannotSend() {
		Long id = queued();
		var stale = outbox.claim(id);
		assertThat(stale).as("New job must be immediately due: next=%s now=%s",
			job(id).nextAttemptAt(), clock.instant()).isNotNull();
		expireLease(id);
		worker().recoverPending();
		assertThat(outbox.beginSending(stale)).isFalse();
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.SENT);
		verify(sender, times(1)).send(any());
	}

	@Test void crashedSendingLeaseBecomesUnknownAndCannotAutomaticallyResend() {
		Long id = queued();
		var claim = outbox.claim(id);
		assertThat(claim).as("New job must be immediately due: next=%s now=%s",
			job(id).nextAttemptAt(), clock.instant()).isNotNull();
		assertThat(outbox.beginSending(claim)).isTrue();
		expireLease(id);
		worker().recoverPending();
		worker().kick(id);
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.UNKNOWN);
		assertThat(deliveries.findById(id).orElseThrow().getErrorSummary()).isEqualTo("DELIVERY_RESULT_UNKNOWN");
		assertPayloadRemoved(id);
		verify(sender, never()).send(any());
		outbox.sent(claim, "known-late-receipt");
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.SENT);
	}

	@Test void failedReceiptTransactionCannotResendProviderAcceptedMailAfterRestart() {
		Long id = queued();
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("r".repeat(300)));
		worker().kick(id); // Force the VARCHAR(255) history write to fail after a provider response.
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.SENDING);
		assertThat(deliveries.findById(id).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.QUEUED);
		expireLease(id);
		worker().recoverPending();
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.UNKNOWN);
		assertPayloadRemoved(id);
		verify(sender, times(1)).send(any());
	}

	@Test void definiteThrottleRetriesAcrossNewWorkersAndStopsAtBoundedAttemptCount() {
		Long id = queued();
		when(sender.send(any())).thenThrow(new EmailSendRejection(true));
		worker().kick(id);
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.RETRY);
		for (int attempt = 2; attempt <= 3; attempt++) {
			jdbc.update("update email_outbox set next_attempt_at = '2000-01-01 00:00:00' where delivery_id = ?", id);
			worker().recoverPending();
		}
		worker().recoverPending();
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(job(id).getAttemptCount()).isEqualTo(3);
		assertThat(deliveries.findById(id).orElseThrow().getErrorSummary()).isEqualTo("RETRY_EXHAUSTED");
		assertPayloadRemoved(id);
		verify(sender, times(3)).send(any());
	}

	@Test void saturatedExecutorLeavesReadyWorkForRecovery() {
		var saturated = new EmailOutboxWorker(outbox, history, sender, properties,
			task -> { throw new java.util.concurrent.RejectedExecutionException(); });
		Long id = new EmailService(history, outbox, saturated, properties, clock).sendAsync(message());
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.READY);
		assertThat(deliveries.findById(id).orElseThrow().getErrorSummary()).isEqualTo("EXECUTOR_REJECTED_RETRY_PENDING");
		worker().recoverPending();
		assertThat(job(id).getStatus()).isEqualTo(EmailOutboxStatus.SENT);
		verify(sender, times(1)).send(any());
	}

	@Test void rolledBackCallerLeavesAuditButNoSendableOutboxAndUncommittedMailIsInvisible() {
		var id = new java.util.concurrent.atomic.AtomicReference<Long>();
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			id.set(new EmailService(history, outbox, worker(), properties, clock).sendAsync(message()));
			try (var threads = Executors.newSingleThreadExecutor()) {
				threads.submit(() -> worker().recoverPending()).get(10, TimeUnit.SECONDS);
			} catch (Exception error) {
				throw new AssertionError(error);
			}
			verify(sender, never()).send(any());
			transaction.setRollbackOnly();
		});
		assertThat(jobs.findById(id.get())).isEmpty();
		assertThat(deliveries.findById(id.get()).orElseThrow().getErrorSummary()).isEqualTo("CALLER_TRANSACTION_ROLLED_BACK");
		worker().recoverPending();
		verify(sender, never()).send(any());
	}

	@Test void committedCallerDispatchesOnlyAfterCommit() {
		var id = new java.util.concurrent.atomic.AtomicReference<Long>();
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			id.set(new EmailService(history, outbox, worker(), properties, clock).sendAsync(message()));
			verify(sender, never()).send(any());
		});
		assertThat(job(id.get()).getStatus()).isEqualTo(EmailOutboxStatus.SENT);
		verify(sender, times(1)).send(any());
	}

	@Test void expiredOrUnreadableSecretPayloadIsNotSentAndIsPurged() {
		Long expired = history.queue(message());
		outbox.enqueue(expired, message(), clock.instant().minusSeconds(1));
		Long unreadable = queued();
		jdbc.update("update email_outbox set encrypted_payload = ? where delivery_id = ?", new byte[] {1,2,3}, unreadable);
		worker().recoverPending();
		assertThat(job(expired).getLastErrorCode()).isEqualTo("PAYLOAD_EXPIRED");
		assertThat(job(unreadable).getLastErrorCode()).isEqualTo("PAYLOAD_UNREADABLE");
		assertPayloadRemoved(expired);
		assertPayloadRemoved(unreadable);
		verify(sender, never()).send(any());
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "MAIL_MYSQL_URL",
		matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/mail_synthetic(?:\\?.*)?$")
	void actualMysqlMigrationPreservesHistoryAndCannotReplayLegacyMail() throws Exception {
		try (var connection = java.sql.DriverManager.getConnection(
			"jdbc:mysql://127.0.0.1:33316/mail_migration_synthetic", "root", "");
			var sql = connection.createStatement()) {
			try (var identity = sql.executeQuery("select @@port, database()")) {
				assertThat(identity.next()).isTrue();
				assertThat(identity.getInt(1)).isEqualTo(33316);
				assertThat(identity.getString(2)).isEqualTo("mail_migration_synthetic");
			}
			sql.execute("drop table if exists email_outbox");
			sql.execute("drop table if exists email_deliveries");
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V43__email_deliveries.sql"));
			sql.execute("insert into email_deliveries(id,recipient,type,status,subject,created_at) values(1,'synthetic@example.com','TEST','QUEUED','Synthetic',current_timestamp),(2,'synthetic@example.com','TEST','SENT','Synthetic',current_timestamp)");
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V54__durable_email_outbox.sql"));
			try (var history = sql.executeQuery("select status,error_summary from email_deliveries where id=1")) {
				assertThat(history.next()).isTrue();
				assertThat(history.getString(1)).isEqualTo("FAILED");
				assertThat(history.getString(2)).isEqualTo("LEGACY_PAYLOAD_UNAVAILABLE");
			}
			sql.execute("insert into email_outbox(delivery_id,status,next_attempt_at,expires_at,created_at) values(2,'SENT',current_timestamp,current_timestamp,current_timestamp)");
			sql.execute("delete from email_deliveries where id=2");
			try (var jobs = sql.executeQuery("select count(*) from email_outbox")) {
				assertThat(jobs.next()).isTrue();
				assertThat(jobs.getInt(1)).isZero();
			}
		}
	}

	private Long queued() {
		Long id = history.queue(message());
		outbox.enqueue(id, message(), clock.instant().plusSeconds(1800));
		return id;
	}
	private void expireLease(Long id) { jdbc.update("update email_outbox set lease_until = '2000-01-01 00:00:00' where delivery_id = ?", id); }
	private EmailOutbox job(Long id) { return jobs.findById(id).orElseThrow(); }
	private void assertPayloadRemoved(Long id) { assertThat(jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id = ?", byte[].class, id)).isNull(); }
	private EmailOutboxWorker worker() { return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run); }
	private EmailMessage message() { return new EmailMessage("synthetic@example.com", "Synthetic", "https://dev.uteum.com/reset?token=synthetic-token", null, EmailDeliveryType.PASSWORD_RESET); }
}
