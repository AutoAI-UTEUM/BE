package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {
	private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
	@Mock private EmailDeliveryStore history;
	@Mock private EmailOutboxStore outbox;
	@Mock private EmailOutboxWorker worker;
	@BeforeEach void setUp() { lenient().when(history.queue(any())).thenReturn(42L); }

	@Test void queuesDurablePayloadBeforeKickingWorkerAndUsesSecretLinkExpiry() {
		assertThat(service(true).sendAsync(message())).isEqualTo(42L);
		var order = org.mockito.Mockito.inOrder(outbox, worker);
		order.verify(outbox).enqueue(42L, message(), NOW.plusSeconds(1800));
		order.verify(worker).kick(42L);
	}

	@Test void disabledRecordsFailureWithoutPersistingSensitivePayload() {
		assertThat(service(false).sendAsync(message())).isEqualTo(42L);
		verify(history).failed(42L, "DISABLED");
		verifyNoInteractions(outbox, worker);
	}

	@Test void auditUnavailableRemainsFailSoft() {
		when(history.queue(any())).thenThrow(new IllegalStateException("db"));
		assertThat(service(true).sendAsync(message())).isNull();
		verifyNoInteractions(outbox, worker);
	}

	@Test void invalidRecipientAndExpiredLinkNeverQueueWhileSubjectRemainsBounded() {
		assertThat(service(true).sendAsync(new EmailMessage("bad", "title", "body", null, EmailDeliveryType.TEST))).isNull();
		assertThat(service(true).sendAsync(message(), NOW)).isNull();
		verify(history, never()).queue(any());
		service(true).sendAsync(new EmailMessage("person@example.com", "a".repeat(300), "body", null, EmailDeliveryType.TEST));
		var queued = ArgumentCaptor.forClass(EmailMessage.class);
		verify(history).queue(queued.capture());
		assertThat(queued.getValue().subject()).hasSize(255);
	}

	@Test void afterCommitDispatchAndRollbackAuditAreMutuallyExclusive() {
		withCallerTransaction(() -> {
			service(true).sendAsync(message());
			verify(worker, never()).kick(any());
			var synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
			synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
			verify(history).failed(42L, "CALLER_TRANSACTION_ROLLED_BACK");
			verifyNoInteractions(worker);
		});
	}

	@Test void committedCallerKicksOnlyAfterCommit() {
		withCallerTransaction(() -> {
			service(true).sendAsync(message());
			verifyNoInteractions(worker);
			TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
			verify(worker).kick(42L);
		});
	}

	@Test void failedPayloadInsertIsVisibleToCallerTransactionAndNeverDispatches() {
		doThrow(new IllegalStateException("db")).when(outbox).enqueue(eq(42L), any(), any());
		withCallerTransaction(() -> {
			assertThatThrownBy(() -> service(true).sendAsync(message())).isInstanceOf(IllegalStateException.class);
			verify(history).failed(42L, "OUTBOX_QUEUE_FAILED");
			verifyNoInteractions(worker);
		});
	}

	private void withCallerTransaction(Runnable body) {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		try { body.run(); } finally { TransactionSynchronizationManager.clear(); }
	}
	private EmailService service(boolean enabled) {
		return new EmailService(history, outbox, worker,
			new MailProperties(enabled, "logging", "test@example.com", "", "https://dev.uteum.com", "ap-northeast-2"),
			Clock.fixed(NOW, ZoneOffset.UTC));
	}
	private EmailMessage message() {
		return new EmailMessage("person@example.com", "Test", "token=synthetic-secret", null, EmailDeliveryType.PASSWORD_RESET);
	}
}
