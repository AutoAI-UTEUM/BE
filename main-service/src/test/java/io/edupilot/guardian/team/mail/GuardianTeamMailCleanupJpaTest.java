package io.edupilot.guardian.team.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryStatus;
import io.edupilot.mail.EmailDeliveryStore;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailMessage;
import io.edupilot.mail.EmailOutbox;
import io.edupilot.mail.EmailOutboxRepository;
import io.edupilot.mail.EmailOutboxStatus;
import io.edupilot.mail.EmailOutboxStore;
import io.edupilot.mail.EmailOutboxWorker;
import io.edupilot.mail.EmailPayloadCipher;
import io.edupilot.mail.EmailQuotaLock;
import io.edupilot.mail.EmailQuotaLockRepository;
import io.edupilot.mail.EmailSendReservation;
import io.edupilot.mail.EmailSendReservationRepository;
import io.edupilot.mail.EmailSender;
import io.edupilot.mail.MailProperties;
import jakarta.persistence.EntityManager;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:guardian-team-mail-cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/guardian-team-mail-cleanup",
	"edupilot.mail.enabled=true", "edupilot.mail.provider=logging",
	"edupilot.mail.outbox.recovery-delay-ms=3600000", "edupilot.guardian.team.enabled=false"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuardianTeamMailCleanupJpaTest {
	private static final String REQUEST = "5ce9b8b6-f1a8-49c0-b8cf-384a5eef3141";
	private static final String OTHER_REQUEST = "b6e7d648-230a-4d25-87f1-377d8392ac1a";
	@Autowired private GuardianTeamMailCleanup cleanup;
	@Autowired private GuardianTeamMailBindingRepository bindings;
	@Autowired private EmailDeliveryRepository deliveries;
	@Autowired private EmailOutboxRepository jobs;
	@Autowired private EmailOutboxStore outbox;
	@Autowired private EmailDeliveryStore history;
	@Autowired private EmailSendReservationRepository reservations;
	@Autowired private EmailQuotaLockRepository quotaLocks;
	@Autowired private EmailPayloadCipher cipher;
	@Autowired private MailProperties properties;
	@Autowired private Clock clock;
	@Autowired private EntityManager entities;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private EmailSender sender;

	@BeforeEach
	void resetSyntheticMail() {
		bindings.deleteAll();
		jobs.deleteAll();
		reservations.deleteAll();
		deliveries.deleteAll();
		if (!quotaLocks.existsById(1)) { quotaLocks.saveAndFlush(EmailQuotaLock.initial()); }
	}

	@Test
	void onlyBoundGuardianCopiesLoseContactPayloadAndReferencesWhileGlobalQuotaRemains() {
		long bound = copy(REQUEST);
		long otherGuardian = copy(OTHER_REQUEST);
		long ordinary = ordinaryMail();
		jdbc.update("update email_deliveries set provider_message_id='synthetic-provider-ref', "
			+ "error_summary='synthetic-contact@example.test', attempt_count=1 where id=?", bound);
		reservations.saveAndFlush(EmailSendReservation.reserved(deliveries.getReferenceById(bound),
			UUID.randomUUID().toString(), clock.instant()));

		purge(REQUEST);
		purge(REQUEST);

		EmailDelivery erased = deliveries.findById(bound).orElseThrow();
		assertThat(erased.getRecipient()).isEmpty();
		assertThat(erased.getSubject()).isEmpty();
		assertThat(erased.getProviderMessageId()).isNull();
		assertThat(erased.getErrorSummary()).isEqualTo("GUARDIAN_CONTACT_ERASED");
		assertThat(erased.getAttemptCount()).isEqualTo(1);
		assertThat(erased.getStatus()).isEqualTo(EmailDeliveryStatus.FAILED);
		assertThat(jobs.findById(bound).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(payload(bound)).isNull();
		assertThat(bindings.findById(bound)).isEmpty();
		assertThat(reservations.countDailyReservations(Instant.EPOCH)).isEqualTo(1);
		assertThat(reservations.countRecipientReservations("guardian@example.test", Instant.EPOCH)).isZero();
		assertThat(deliveries.findById(otherGuardian).orElseThrow().getRecipient()).isEqualTo("guardian@example.test");
		assertThat(payload(otherGuardian)).isNotEmpty();
		assertThat(deliveries.findById(ordinary).orElseThrow().getRecipient()).isEqualTo("unrelated@example.test");
		assertThat(payload(ordinary)).isNotEmpty();
	}

	@Test
	void erasedClaimsCannotStartRetryOrRestoreLateReceiptsAcrossFreshWorkers() {
		long claimed = copy(REQUEST);
		long sending = copy(REQUEST);
		EmailOutboxStore.Claim claim = oldClaim(claimed, "CLAIMED");
		EmailOutboxStore.Claim alreadySending = oldClaim(sending, "SENDING");
		purge(REQUEST);

		assertThat(outbox.beginSending(claim)).isFalse();
		outbox.sent(alreadySending, "late-synthetic-provider-ref");
		outbox.rejected(alreadySending, true);
		outbox.unknown(alreadySending);
		history.sent(sending, "late-direct-history-ref");
		history.failed(sending, "late-personal-error@example.test");
		worker().kick(claimed);
		worker().kick(sending);
		worker().recoverPending();

		verify(sender, never()).send(any());
		assertThat(jobs.findById(claimed).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.FAILED);
		assertThat(jobs.findById(sending).orElseThrow().getStatus()).isEqualTo(EmailOutboxStatus.UNKNOWN);
		for (long id : new long[] {claimed, sending}) {
			assertThat(jdbc.queryForObject("select lease_token from email_outbox where delivery_id=?", String.class, id)).isNull();
			assertThat(deliveries.findById(id).orElseThrow().getProviderMessageId()).isNull();
			assertThat(deliveries.findById(id).orElseThrow().getErrorSummary()).isEqualTo("GUARDIAN_CONTACT_ERASED");
			assertThat(payload(id)).isNull();
		}
	}

	@Test
	void unsupportedGuardianCopiesCannotBeClaimedOrSentByRecoveryBeforeCleanupEither() {
		long id = copy(REQUEST);
		assertThat(outbox.claim(id)).isNull();
		EmailOutboxStore.Claim forged = oldClaim(id, "CLAIMED");
		EmailOutboxStore.Claim wrongPurpose = new EmailOutboxStore.Claim(forged.id(), forged.token(), ordinaryMessage());
		assertThat(outbox.beginSending(wrongPurpose)).isFalse();
		worker().kick(id);
		worker().recoverPending();
		verify(sender, never()).send(any());
		assertThat(deliveries.findById(id).orElseThrow().getAttemptCount()).isZero();
	}

	@Test
	void noOutboxAndAlreadySentHistoryCanBePurgedWithoutDeletingQuotaHistory() {
		long id = copy(REQUEST);
		jdbc.update("update email_deliveries set status='SENT',sent_at=?,provider_message_id='synthetic-ref' where id=?",
			Timestamp.from(clock.instant()), id);
		jobs.deleteById(id);
		purge(REQUEST);
		EmailDelivery erased = deliveries.findById(id).orElseThrow();
		assertThat(erased.getStatus()).isEqualTo(EmailDeliveryStatus.SENT);
		assertThat(erased.getRecipient()).isEmpty();
		assertThat(erased.getSentAt()).isNull();
		assertThat(erased.getProviderMessageId()).isNull();
	}

	@Test
	void rollbackRestoresCaseBindingsAndCopiesTogetherAndMissingTransactionIsRejected() {
		long id = copy(REQUEST);
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			cleanup.purgeForRequest(REQUEST);
			transaction.setRollbackOnly();
		});
		assertThat(bindings.findById(id)).isPresent();
		assertThat(deliveries.findById(id).orElseThrow().getRecipient()).isEqualTo("guardian@example.test");
		assertThat(payload(id)).isNotEmpty();
		assertThatThrownBy(() -> cleanup.purgeForRequest(REQUEST)).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void unsupportedGenericHistoryAndOutboxCannotPersistGuardianCopiesEvenWithAChangedPayloadType() {
		assertThatThrownBy(() -> history.queue(message())).isInstanceOf(IllegalArgumentException.class);
		assertThat(deliveries.count()).isZero();
		long id = copy(REQUEST);
		jobs.deleteById(id);
		assertThatThrownBy(() -> outbox.enqueue(id, ordinaryMessage(), clock.instant().plusSeconds(600)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jobs.findById(id)).isEmpty();
		long ordinary = history.queue(ordinaryMessage());
		assertThatThrownBy(() -> outbox.enqueue(ordinary, message(), clock.instant().plusSeconds(600)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jobs.findById(ordinary)).isEmpty();
		assertThat(history.reserve(id, UUID.randomUUID().toString())).isFalse();
		assertThat(reservations.count()).isZero();
	}

	@Test
	void internalBindingRejectsOtherPurposesAndDeliveryCannotBelongToTwoCases() {
		long ordinary = ordinaryMail();
		assertThatThrownBy(() -> GuardianTeamMailBinding.trustedCopy(REQUEST,
			deliveries.findById(ordinary).orElseThrow(), clock.instant())).isInstanceOf(IllegalArgumentException.class);
		long id = copy(REQUEST);
		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
			entities.persist(GuardianTeamMailBinding.trustedCopy(OTHER_REQUEST,
				deliveries.getReferenceById(id), clock.instant()));
			entities.flush();
		})).isInstanceOf(RuntimeException.class);
		new TransactionTemplate(transactions).executeWithoutResult(transaction ->
			assertThat(bindings.findForRequestUpdate(REQUEST)).hasSize(1));
	}

	@Test
	void corruptedPurposeBindingFailsClosedWithoutErasingUnrelatedContact() {
		long id = copy(REQUEST);
		jdbc.update("update email_deliveries set type='NOTIFICATION' where id=?", id);
		assertThatThrownBy(() -> purge(REQUEST)).isInstanceOf(IllegalStateException.class);
		assertThat(deliveries.findById(id).orElseThrow().getRecipient()).isEqualTo("guardian@example.test");
		assertThat(bindings.findById(id)).isPresent();
		assertThat(payload(id)).isNotEmpty();
	}

	private long copy(String requestId) {
		return new TransactionTemplate(transactions).execute(transaction -> {
			EmailDelivery delivery = deliveries.saveAndFlush(EmailDelivery.queued(message(), clock.instant()));
			entities.persist(EmailOutbox.queued(delivery, cipher.encrypt(delivery.getId(), message()),
				clock.instant(), clock.instant().plusSeconds(600)));
			entities.persist(GuardianTeamMailBinding.trustedCopy(requestId, delivery, clock.instant()));
			entities.flush();
			return delivery.getId();
		});
	}
	private long ordinaryMail() {
		long id = history.queue(ordinaryMessage());
		outbox.enqueue(id, ordinaryMessage(), clock.instant().plusSeconds(600));
		return id;
	}
	private EmailOutboxStore.Claim oldClaim(long id, String status) {
		String token = UUID.randomUUID().toString();
		jdbc.update("update email_outbox set status=?,lease_token=?,lease_until=? where delivery_id=?",
			status, token, Timestamp.from(clock.instant().plusSeconds(60)), id);
		return new EmailOutboxStore.Claim(id, token, message());
	}
	private void purge(String requestId) {
		new TransactionTemplate(transactions).executeWithoutResult(transaction -> cleanup.purgeForRequest(requestId));
	}
	private byte[] payload(long id) {
		return jdbc.queryForObject("select encrypted_payload from email_outbox where delivery_id=?", byte[].class, id);
	}
	private EmailMessage message() {
		return new EmailMessage("guardian@example.test", "합성 보호자 안내", "합성 연락처와 비밀 신청 링크", null,
			EmailDeliveryType.GUARDIAN_TEAM_NOTICE);
	}
	private EmailMessage ordinaryMessage() {
		return new EmailMessage("unrelated@example.test", "무관한 메일", "합성 일반 본문", null, EmailDeliveryType.TEST);
	}
	private EmailOutboxWorker worker() {
		return new EmailOutboxWorker(outbox, history, sender, properties, Runnable::run);
	}
}
