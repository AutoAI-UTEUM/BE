package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

	@Mock private EmailDeliveryStore store;
	@Mock private EmailSender sender;

	@BeforeEach
	void setUp() {
		lenient().when(store.queue(any())).thenReturn(42L);
	}

	@Test
	void successRecordsSentAndProviderId() {
		when(store.reserve(42L)).thenReturn(true);
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("ses-123"));

		assertThat(service(true, Runnable::run).sendAsync(message())).isEqualTo(42L);
		verify(store).attempt(42L);
		verify(store).sent(42L, "ses-123");
		verify(store, never()).failed(eq(42L), any());
	}

	@Test
	void retriesOnceAndRecordsSecondAttemptSuccess() {
		when(store.reserve(42L)).thenReturn(true);
		when(sender.send(any()))
			.thenThrow(new IllegalStateException("temporary"))
			.thenReturn(new EmailDeliveryResult("ses-456"));

		service(true, Runnable::run).sendAsync(message());

		verify(sender, times(2)).send(any());
		verify(store, times(2)).attempt(42L);
		verify(store).sent(42L, "ses-456");
	}

	@Test
	void twoFailuresRecordRedactedSummaryWithoutPropagating() {
		when(store.reserve(42L)).thenReturn(true);
		when(sender.send(any())).thenThrow(new IllegalStateException(
			"failure: https://dev.uteum.com/reset?token=secret body-secret " + "x".repeat(300)
		));

		assertThatCode(() -> service(true, Runnable::run).sendAsync(message()))
			.doesNotThrowAnyException();

		ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
		verify(store).failed(eq(42L), summary.capture());
		assertThat(summary.getValue())
			.startsWith("IllegalStateException:")
			.doesNotContain("body-secret", "token=secret", "https://")
			.hasSizeLessThanOrEqualTo(200);
	}

	@Test
	void rateLimitedRequestDoesNotCallSender() {
		when(store.reserve(42L)).thenReturn(false);

		service(true, Runnable::run).sendAsync(message());

		verify(sender, never()).send(any());
	}

	@Test
	void disabledRecordsFailureAndNeverSends() {
		assertThat(service(false, Runnable::run).sendAsync(message())).isEqualTo(42L);
		verify(store).failed(42L, "DISABLED");
		verify(sender, never()).send(any());
	}

	@Test
	void returnsBeforeSlowSenderRuns() {
		AtomicReference<Runnable> queued = new AtomicReference<>();
		Executor deferred = queued::set;
		when(store.reserve(42L)).thenReturn(true);
		when(sender.send(any())).thenReturn(new EmailDeliveryResult("ses-789"));

		assertThat(service(true, deferred).sendAsync(message())).isEqualTo(42L);
		verify(sender, never()).send(any());
		queued.get().run();
		verify(store).sent(42L, "ses-789");
	}

	@Test
	void queueFailureDoesNotFailCaller() {
		when(store.queue(any())).thenThrow(new IllegalStateException("database down"));
		assertThat(service(true, Runnable::run).sendAsync(message())).isNull();
		verify(sender, never()).send(any());
	}

	@Test
	void invalidRecipientNeverQueuesAndLongSubjectIsTruncated() {
		assertThat(service(true, Runnable::run).sendAsync(new EmailMessage(
			"bad-address", "test", "body", null, EmailDeliveryType.TEST
		))).isNull();
		verify(store, never()).queue(any());

		AtomicReference<Runnable> queued = new AtomicReference<>();
		service(true, queued::set).sendAsync(new EmailMessage(
			"person@example.com", "a".repeat(300), "body", null,
			EmailDeliveryType.TEST
		));
		ArgumentCaptor<EmailMessage> saved = ArgumentCaptor.forClass(EmailMessage.class);
		verify(store).queue(saved.capture());
		assertThat(saved.getValue().subject()).hasSize(255);
	}

	@Test
	void executorFailureMarksHistoryFailedWithoutFailingCaller() {
		Executor rejecting = task -> { throw new RejectedExecutionException("busy"); };
		assertThatCode(() -> service(true, rejecting).sendAsync(message()))
			.doesNotThrowAnyException();
		verify(store).failed(42L, "EXECUTOR_REJECTED");
		verify(sender, never()).send(any());
	}

	private EmailService service(boolean enabled, Executor executor) {
		return new EmailService(store, sender, executor, new MailProperties(
			enabled, "ses", "no-reply@uteum.com", "", "https://www.uteum.com",
			"ap-northeast-2"
		));
	}

	private EmailMessage message() {
		return new EmailMessage(
			"person@example.com", "Test", "body-secret", null, EmailDeliveryType.TEST
		);
	}
}
