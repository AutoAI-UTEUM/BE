package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryStoreTest {

	@Mock private EmailDeliveryRepository repository;
	@Mock private EmailQuotaLockRepository locks;
	@Mock private EmailSendReservationRepository reservations;

	@BeforeEach void setup() {
		when(locks.findForUpdate(1)).thenReturn(Optional.of(EmailQuotaLock.initial()));
	}

	@Test
	void sixthRecipientMailIsRateLimited() {
		Instant now = Instant.parse("2026-09-24T01:00:00Z");
		EmailDelivery delivery = delivery(now);
		when(repository.findForUpdate(6L)).thenReturn(Optional.of(delivery));
		when(reservations.countRecipientReservations(eq("user@example.com"), any())).thenReturn(5L);
		when(reservations.countDailyReservations(any())).thenReturn(5L);

		assertThat(store(now).reserve(6L, "claim")).isFalse();
		assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.RATE_LIMITED);
		verify(reservations, never()).saveAndFlush(any());
	}

	@Test
	void fifthRecipientAndFiveHundredthGlobalAttemptUseRollingHourAndKstMidnight() {
		Instant now = Instant.parse("2026-09-24T15:01:00Z");
		EmailDelivery delivery = delivery(now);
		when(repository.findForUpdate(501L)).thenReturn(Optional.of(delivery));
		when(reservations.countRecipientReservations(eq("user@example.com"), any())).thenReturn(4L);
		when(reservations.countDailyReservations(any())).thenReturn(499L);

		assertThat(store(now).reserve(501L, "claim")).isTrue();
		verify(reservations).countRecipientReservations("user@example.com", now.minusSeconds(3600));
		verify(reservations).countDailyReservations(Instant.parse("2026-09-24T15:00:00Z"));
		verify(reservations).saveAndFlush(any());
		assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.QUEUED);
	}

	@Test
	void fiveHundredAndFirstDailyMailIsRateLimited() {
		Instant now = Instant.parse("2026-09-24T14:59:00Z");
		EmailDelivery delivery = delivery(now);
		when(repository.findForUpdate(501L)).thenReturn(Optional.of(delivery));
		when(reservations.countRecipientReservations(eq("user@example.com"), any())).thenReturn(0L);
		when(reservations.countDailyReservations(any())).thenReturn(500L);

		assertThat(store(now).reserve(501L, "claim")).isFalse();
		verify(reservations).countDailyReservations(Instant.parse("2026-09-23T15:00:00Z"));
	}

	@Test void repeatedClaimDoesNotConsumeAnotherQuotaUnit() {
		Instant now = Instant.parse("2026-09-24T14:59:00Z");
		when(repository.findForUpdate(501L)).thenReturn(Optional.of(delivery(now)));
		when(reservations.existsByDeliveryIdAndClaimToken(501L, "claim")).thenReturn(true);
		assertThat(store(now).reserve(501L, "claim")).isTrue();
		verify(reservations, never()).countDailyReservations(any());
		verify(reservations, never()).saveAndFlush(any());
	}

	@Test void missingMigrationSingletonFailsClosed() {
		Instant now = Instant.parse("2026-09-24T14:59:00Z");
		when(locks.findForUpdate(1)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> store(now).reserve(501L, "claim")).isInstanceOf(IllegalStateException.class);
		verify(reservations, never()).saveAndFlush(any());
	}

	private EmailDeliveryStore store(Instant now) {
		return new EmailDeliveryStore(repository, locks, reservations, Clock.fixed(now, ZoneOffset.UTC));
	}

	private EmailDelivery delivery(Instant now) {
		EmailDelivery delivery = EmailDelivery.queued(new EmailMessage(
			"user@example.com", "test", "body", null, EmailDeliveryType.TEST
		), now);
		ReflectionTestUtils.setField(delivery, "id", 501L);
		return delivery;
	}
}
