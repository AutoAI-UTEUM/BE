package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailOutboxRecoveryIsolationTest {
	@Mock private EmailOutboxStore outbox;
	@Mock private EmailDeliveryStore history;
	@Mock private EmailSender sender;
	private final List<Runnable> scheduled = new ArrayList<>();

	@Test
	void oneFailedRowCannotBlockLaterCleanupOrDispatch(CapturedOutput output) {
		when(outbox.cleanupIds()).thenReturn(List.of(1L, 2L, 3L));
		when(outbox.dispatchableIds()).thenReturn(List.of(42L, 43L));
		doThrow(new IllegalStateException("synthetic@example.test token=synthetic-private-body"))
			.when(outbox).recover(1L);

		worker(true).recoverPending();

		verify(outbox).recover(2L);
		verify(outbox).recover(3L);
		assertThat(scheduled).hasSize(2);
		assertThat(output).contains("Mail outbox row recovery unavailable")
			.doesNotContain("synthetic@example.test", "synthetic-private-body");
	}

	@Test
	void failedDurableRowIsRetriedOnTheNextSweep() {
		when(outbox.cleanupIds()).thenReturn(List.of(1L, 2L));
		when(outbox.dispatchableIds()).thenReturn(List.of());
		doThrow(new IllegalStateException("synthetic row unavailable")).doNothing().when(outbox).recover(1L);
		EmailOutboxWorker worker = worker(true);

		worker.recoverPending();
		worker.recoverPending();

		verify(outbox, times(2)).recover(1L);
		verify(outbox, times(2)).recover(2L);
		assertThat(scheduled).isEmpty();
	}

	@Test
	void sendingDisabledStillRecoversOtherRowsAfterFailure() {
		when(outbox.cleanupIds()).thenReturn(List.of(1L, 2L));
		doThrow(new IllegalStateException("synthetic row unavailable")).when(outbox).recover(1L);

		worker(false).recoverPending();

		verify(outbox).recover(2L);
		verify(outbox, never()).dispatchableIds();
		assertThat(scheduled).isEmpty();
	}

	@Test
	void failedBatchSelectionCanRecoverAtTheNextSweepWithoutSending(CapturedOutput output) {
		when(outbox.cleanupIds()).thenThrow(new IllegalStateException("token=synthetic-selection-secret"))
			.thenReturn(List.of(1L));
		when(outbox.dispatchableIds()).thenReturn(List.of(42L));
		EmailOutboxWorker worker = worker(true);

		worker.recoverPending();
		assertThat(scheduled).isEmpty();
		worker.recoverPending();

		verify(outbox).recover(1L);
		assertThat(scheduled).hasSize(1);
		assertThat(output).contains("Mail outbox recovery unavailable")
			.doesNotContain("synthetic-selection-secret");
		verify(sender, never()).send(org.mockito.ArgumentMatchers.any());
	}

	private EmailOutboxWorker worker(boolean enabled) {
		return new EmailOutboxWorker(outbox, history, sender,
			new MailProperties(enabled, "logging", "synthetic@example.test", "", "https://dev.uteum.com", "ap-northeast-2"),
			scheduled::add);
	}
}
