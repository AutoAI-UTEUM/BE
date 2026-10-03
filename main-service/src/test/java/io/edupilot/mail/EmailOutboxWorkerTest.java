package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailOutboxWorkerTest {
	@Mock private EmailOutboxStore outbox;
	@Mock private EmailDeliveryStore history;
	@Mock private EmailSender sender;
	private final EmailOutboxStore.Claim claim = new EmailOutboxStore.Claim(42L, "fence", new EmailMessage(
		"synthetic@example.com", "title", "https://dev.uteum.com/reset?token=private-body", null, EmailDeliveryType.PASSWORD_RESET));
	@BeforeEach void setup() {
		lenient().when(outbox.claim(42L)).thenReturn(claim);
		lenient().when(history.reserve(42L)).thenReturn(true);
		lenient().when(outbox.beginSending(claim)).thenReturn(true);
	}
	@Test void successfulSendRecordsReceiptOnce() {
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("receipt"));
		worker(Runnable::run).kick(42L);
		verify(outbox).sent(claim, "receipt");
		verify(sender, times(1)).send(any());
	}
	@Test void unavailableClaimOrQuotaOrExpiredLeaseCannotSend() {
		when(outbox.claim(42L)).thenReturn(null);
		worker(Runnable::run).kick(42L);
		when(outbox.claim(42L)).thenReturn(claim);
		when(history.reserve(42L)).thenReturn(false);
		worker(Runnable::run).kick(42L);
		verify(outbox).rateLimited(claim);
		when(history.reserve(42L)).thenReturn(true);
		when(outbox.beginSending(claim)).thenReturn(false);
		worker(Runnable::run).kick(42L);
		verify(sender, never()).send(any());
	}
	@Test void throttleSchedulesDurableRetryInsteadOfAnImmediateSecondSend() {
		when(sender.send(any())).thenThrow(new EmailSendRejection(true));
		worker(Runnable::run).kick(42L);
		verify(outbox).rejected(claim, true);
		verify(sender, times(1)).send(any());
	}
	@Test void definiteRejectionDoesNotRetry() {
		when(sender.send(any())).thenThrow(new EmailSendRejection(false));
		worker(Runnable::run).kick(42L);
		verify(outbox).rejected(claim, false);
	}
	@Test void ambiguousProviderFailureIsTrackedWithoutSecretLogsOrBlindResend(CapturedOutput output) {
		when(sender.send(any())).thenThrow(new IllegalStateException("provider lost response token=private-body " + claim.message().textBody()));
		worker(Runnable::run).kick(42L);
		verify(outbox).unknown(claim);
		verify(sender, times(1)).send(any());
		assertThat(output).contains("outcome unknown").doesNotContain("private-body", "https://dev.uteum.com/reset", "synthetic@example.com");
		assertThat(claim.toString()).doesNotContain("private-body", "fence");
	}
	@Test void failedReceiptWriteNeverCausesAnotherProviderCall() {
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("receipt"));
		doThrow(new IllegalStateException("db")).when(outbox).sent(claim, "receipt");
		worker(Runnable::run).kick(42L);
		verify(sender, times(1)).send(any());
		verify(outbox, never()).rejected(any(), org.mockito.ArgumentMatchers.anyBoolean());
	}
	@Test void executorSaturationLeavesDurableWorkAndAllowsLaterDispatch() {
		Executor rejecting = task -> { throw new RejectedExecutionException(); };
		EmailOutboxWorker worker = worker(rejecting);
		worker.kick(42L);
		worker.kick(42L);
		verify(outbox, times(2)).executorRejected(42L);
		verify(sender, never()).send(any());
	}
	@Test void duplicateQueuedIdsDoNotFillExecutorWithTheSameWork() {
		AtomicReference<Runnable> pending = new AtomicReference<>();
		EmailOutboxWorker worker = worker(pending::set);
		worker.kick(42L);
		Runnable first = pending.get();
		worker.kick(42L);
		assertThat(pending.get()).isSameAs(first);
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("receipt"));
		first.run();
		worker.kick(42L);
		assertThat(pending.get()).isNotSameAs(first);
	}
	private EmailOutboxWorker worker(Executor executor) {
		return new EmailOutboxWorker(outbox, history, sender,
			new MailProperties(true, "logging", "test@example.com", "", "https://dev.uteum.com", "ap-northeast-2"), executor);
	}
}
