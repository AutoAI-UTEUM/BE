package io.edupilot.aiusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.UserRole;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-29T01:23:45Z");

	@Mock
	private AiUsageLogRepository repository;

	@Test
	void defaultQuotaPassesBelowLimitAndRejectsAtLimit() {
		when(repository.countByUserIdAndCreatedAtGreaterThanEqual(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(199L, 200L);
		AiQuotaService service = service(true);

		assertThatCode(() -> service.checkQuota(1L, UserRole.LEARNER))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> service.checkQuota(1L, UserRole.LEARNER))
			.isInstanceOfSatisfying(BusinessException.class, exception -> {
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED);
				assertThat(exception.errorCode().status().value()).isEqualTo(429);
			});
	}

	@Test
	void instructorUsesSeparateLimit() {
		when(repository.countByUserIdAndCreatedAtGreaterThanEqual(
			org.mockito.ArgumentMatchers.eq(2L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(499L, 500L);
		AiQuotaService service = service(true);

		assertThatCode(() -> service.checkQuota(2L, UserRole.INSTRUCTOR))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> service.checkQuota(2L, UserRole.INSTRUCTOR))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode())
					.isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED)
			);
	}

	@Test
	void adminIsAlwaysExempt() {
		AiQuotaService service = service(true);

		assertThatCode(() -> service.checkQuota(3L, UserRole.ADMIN))
			.doesNotThrowAnyException();
		verifyNoInteractions(repository);
	}

	@Test
	void disabledQuotaAlwaysPasses() {
		AiQuotaService service = service(false);

		assertThatCode(() -> service.checkQuota(1L, UserRole.LEARNER))
			.doesNotThrowAnyException();
		verifyNoInteractions(repository);
	}

	@Test
	void countsOnlyRowsFromCurrentKstMidnight() {
		when(repository.countByUserIdAndCreatedAtGreaterThanEqual(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.any()
		)).thenReturn(0L);

		service(true).checkQuota(1L, UserRole.LEARNER);

		ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
		verify(repository).countByUserIdAndCreatedAtGreaterThanEqual(
			org.mockito.ArgumentMatchers.eq(1L),
			since.capture()
		);
		assertThat(since.getValue())
			.isEqualTo(Instant.parse("2026-08-28T15:00:00Z"));
	}

	private AiQuotaService service(boolean enabled) {
		return new AiQuotaService(
			repository,
			new AiQuotaProperties(enabled, 200, 500),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}
}
