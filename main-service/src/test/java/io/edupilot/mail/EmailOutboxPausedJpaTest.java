package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:mail-dispatch-paused;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/mail-dispatch-paused",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.outbox.recovery-delay-ms=3600000",
	"edupilot.mail.outbox.dispatch.mode=PAUSED"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailOutboxPausedJpaTest {
	@Autowired private EmailService service;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailOutboxWorker worker;
	@Autowired private EmailSendReservationRepository reservations;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Clock clock;
	@Autowired private PlatformTransactionManager transactions;
	@MockitoBean private EmailSender sender;

	@BeforeEach void setup() { jobs.deleteAll(); deliveries.deleteAll(); }

	@Test void newlyCommittedMailRemainsDurableWithoutSchedulingOrReservingProviderWork() {
		AtomicReference<Long> id = new AtomicReference<>();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> id.set(service.sendAsync(message())));
		worker.kick(id.get());
		worker.recoverPending();
		assertThat(outbox.dispatchableIds()).isEmpty();
		assertThat(outbox.claim(id.get())).isNull();
		assertThat(jobs.findById(id.get()).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.READY);
		assertThat(deliveries.findById(id.get()).orElseThrow().getStatus()).isEqualTo(EmailDeliveryStatus.QUEUED);
		assertThat(jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id=?",
			byte[].class, id.get())).isNotEmpty();
		assertThat(reservations.count()).isZero();
		verify(sender, never()).send(any());
	}

	@Test void pausedWorkStillExpiresAndPurgesItsPayloadWithoutConsumingQuota() {
		Long id = service.sendAsync(message(), clock.instant().plusSeconds(30));
		jdbc.update("update email_outbox set expires_at='2000-01-01 00:00:00' where delivery_id=?", id);
		worker.recoverPending();
		assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(jobs.findById(id).orElseThrow().getLastErrorCode()).isEqualTo("PAYLOAD_EXPIRED");
		assertThat(jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id=?",
			byte[].class, id)).isNull();
		assertThat(reservations.count()).isZero();
		verify(sender, never()).send(any());
	}

	@Test void callerRollbackDoesNotLeaveDurablePayloadEvenWhileDispatchIsPaused() {
		AtomicReference<Long> id = new AtomicReference<>();
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			id.set(service.sendAsync(message()));
			tx.setRollbackOnly();
		});
		assertThat(jobs.findById(id.get())).isEmpty();
		assertThat(deliveries.findById(id.get()).orElseThrow().getErrorSummary())
			.isEqualTo("CALLER_TRANSACTION_ROLLED_BACK");
		worker.recoverPending();
		verify(sender, never()).send(any());
	}

	private EmailMessage message() {
		return new EmailMessage("trial@example.test", "Synthetic", "Synthetic reset body", null,
			EmailDeliveryType.PASSWORD_RESET);
	}
}
