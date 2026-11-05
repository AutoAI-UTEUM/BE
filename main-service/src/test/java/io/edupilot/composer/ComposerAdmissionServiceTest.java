package io.edupilot.composer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.edupilot.ai.dto.AiUsage;
import io.edupilot.aiusage.*;
import io.edupilot.user.*;

class ComposerAdmissionServiceTest {
	final UserRepository users = mock(UserRepository.class);
	final AiUsageLogRepository usage = mock(AiUsageLogRepository.class);
	final AiQuotaService quota = mock(AiQuotaService.class);
	final Instant now = Instant.parse("2026-11-04T00:00:00Z");
	final ComposerProperties policy = policy();
	final ComposerAdmissionService service = new ComposerAdmissionService(users, usage, quota, policy, Clock.fixed(now, ZoneOffset.UTC));

	static ComposerProperties policy() { return new ComposerProperties(true, true, 10, 3, 1, 1000, 100, Duration.ofSeconds(10)); }
	@BeforeEach void setup() { when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(User.class))); }
	boolean reserve() { return service.reserve(1L, UserRole.ADMIN, "synthetic-key"); }

	@Test void committedReservationPrecedesHttpAndAdminHasTheSameLimits() {
		assertThat(reserve()).isTrue();
		verify(quota).checkQuota(1L, UserRole.ADMIN);
		verify(usage).saveAndFlush(argThat(row -> row.getFeature() == AiFeature.COMPOSER_PREDICTION
			&& row.getCostUsdTicks() == null && !row.isSuccess()));
	}
	@Test void durableDuplicateAndFixedConcurrencyLeaseDenyRegeneration() {
		when(usage.existsByRequestId("synthetic-key")).thenReturn(true); assertThat(reserve()).isFalse();
		when(usage.existsByRequestId("synthetic-key")).thenReturn(false);
		when(usage.findComposerUsage(eq(1L), any())).thenReturn(List.of(row(now.minusSeconds(2), 0L)));
		assertThat(reserve()).isFalse(); verify(usage, never()).saveAndFlush(any());
	}
	@Test void unknownCostIsReservedRatherThanZeroAndBudgetCannotOverflow() {
		when(usage.findComposerUsage(eq(1L), any())).thenReturn(List.of(row(now.minusSeconds(100), 850L), row(now.minusSeconds(100), null)));
		assertThat(reserve()).isFalse();
		when(usage.findComposerUsage(eq(1L), any())).thenReturn(List.of(row(now.minusSeconds(100), Long.MAX_VALUE)));
		assertThat(reserve()).isFalse();
	}
	@Test void minuteAndDailyAttemptsIncludeFailuresAndUnknownSpend() {
		when(usage.findComposerUsage(eq(1L), any())).thenReturn(java.util.Collections.nCopies(3, row(now.minusSeconds(30), null)));
		assertThat(reserve()).isFalse();
		when(usage.findComposerUsage(eq(1L), any())).thenReturn(java.util.Collections.nCopies(10, row(now.minusSeconds(100), 0L)));
		assertThat(reserve()).isFalse();
	}
	@Test void previousKoreanDayDoesNotRefundMinuteOrConcurrencyLimits() {
		var boundary = new ComposerAdmissionService(users, usage, quota, policy,
			Clock.fixed(Instant.parse("2026-11-03T15:00:01Z"), ZoneOffset.UTC));
		when(usage.findComposerUsage(eq(1L), any())).thenReturn(List.of(row(Instant.parse("2026-11-03T14:59:59Z"), null)));
		assertThat(boundary.reserve(1L, UserRole.LEARNER, "key")).isFalse();
	}
	@Test void finalizationRefinesTheSameReservationAndSanitizesUnknownFields() {
		var row = AiUsageLog.create(1L, AiFeature.COMPOSER_PREDICTION, null, false, "synthetic-key");
		when(usage.findByUserIdAndFeatureAndRequestId(1L, AiFeature.COMPOSER_PREDICTION, "synthetic-key")).thenReturn(Optional.of(row));
		service.finish(1L, "synthetic-key", new AiUsage("x".repeat(51), -1L, 12L, null, 25L), false);
		assertThat(row.getModel()).isNull(); assertThat(row.getInputTokens()).isNull();
		assertThat(row.getOutputTokens()).isEqualTo(12L); assertThat(row.getCostUsdTicks()).isEqualTo(25L);
		verify(usage, never()).saveAndFlush(any());
	}
	@Test void offUnconfirmedOrUnsetPolicyNeverAdmits() {
		for (var invalid : List.of(new ComposerProperties(false, true, 10, 3, 1, 1000, 100, Duration.ofSeconds(10)),
			new ComposerProperties(true, false, 10, 3, 1, 1000, 100, Duration.ofSeconds(10)),
			new ComposerProperties(true, true, 0, 0, 0, 0, 0, Duration.ofSeconds(10)))) {
			assertThat(new ComposerAdmissionService(users, usage, quota, invalid, Clock.systemUTC())
				.reserve(1L, UserRole.ADMIN, "key")).isFalse();
		}
		verifyNoInteractions(users, usage, quota);
	}
	static AiUsageLogRepository.ComposerUsage row(Instant at, Long cost) {
		return new AiUsageLogRepository.ComposerUsage() {
			public Instant getCreatedAt() { return at; }
			public Long getCostUsdTicks() { return cost; }
		};
	}
}
