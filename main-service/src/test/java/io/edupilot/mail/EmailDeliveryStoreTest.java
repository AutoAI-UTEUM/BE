package io.edupilot.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryStoreTest {

	@Mock private EmailDeliveryRepository repository;

	@Test
	void sixthRecipientMailIsRateLimited() {
		Instant now = Instant.parse("2026-09-24T01:00:00Z");
		EmailDelivery delivery = delivery(now);
		when(repository.findById(6L)).thenReturn(Optional.of(delivery));
		when(repository.countRecipientQuota(eq("user@example.com"), any(), eq(6L)))
			.thenReturn(6L);
		when(repository.countDailyQuota(any(), eq(6L))).thenReturn(6L);

		assertThat(store(now).reserve(6L)).isFalse();
		assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.RATE_LIMITED);
	}

	@Test
	void dailyLimitUsesKstMidnightAndFivePerRecipientIsAllowed() {
		Instant now = Instant.parse("2026-09-24T15:01:00Z");
		EmailDelivery delivery = delivery(now);
		when(repository.findById(501L)).thenReturn(Optional.of(delivery));
		when(repository.countRecipientQuota(eq("user@example.com"), any(), eq(501L)))
			.thenReturn(5L);
		when(repository.countDailyQuota(any(), eq(501L))).thenReturn(500L);

		assertThat(store(now).reserve(501L)).isTrue();
		verify(repository).countDailyQuota(Instant.parse("2026-09-24T15:00:00Z"), 501L);
		assertThat(delivery.getStatus()).isEqualTo(EmailDeliveryStatus.QUEUED);
	}

	@Test
	void fiveHundredAndFirstDailyMailIsRateLimited() {
		Instant now = Instant.parse("2026-09-24T14:59:00Z");
		EmailDelivery delivery = delivery(now);
		when(repository.findById(501L)).thenReturn(Optional.of(delivery));
		when(repository.countRecipientQuota(eq("user@example.com"), any(), eq(501L)))
			.thenReturn(1L);
		when(repository.countDailyQuota(any(), eq(501L))).thenReturn(501L);

		assertThat(store(now).reserve(501L)).isFalse();
		verify(repository).countDailyQuota(Instant.parse("2026-09-23T15:00:00Z"), 501L);
	}

	private EmailDeliveryStore store(Instant now) {
		return new EmailDeliveryStore(repository, Clock.fixed(now, ZoneOffset.UTC));
	}

	private EmailDelivery delivery(Instant now) {
		EmailDelivery delivery = EmailDelivery.queued(new EmailMessage(
			"user@example.com", "test", "body", null, EmailDeliveryType.TEST
		), now);
		ReflectionTestUtils.setField(delivery, "id", 501L);
		return delivery;
	}
}
