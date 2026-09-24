package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.edupilot.admin.xai.dto.UpdateXaiAlertsRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

class XaiAlertConfigServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

	private XaiAlertConfigRepository repository;
	private XaiAlertConfigService service;

	@BeforeEach
	void setUp() {
		repository = mock(XaiAlertConfigRepository.class);
		service = new XaiAlertConfigService(
			repository,
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@Test
	void returnsPhaseOneDefaultsWhenSingletonRowIsMissing() {
		when(repository.findById(XaiAlertConfig.SINGLETON_ID))
			.thenReturn(Optional.empty());

		var response = service.get();

		assertThat(response.balanceCriticalUsd()).isEqualByComparingTo("10");
		assertThat(response.balanceWarningUsd()).isEqualByComparingTo("50");
		assertThat(response.depletionCriticalDays()).isEqualTo(7);
		assertThat(response.depletionWarningDays()).isEqualTo(30);
		assertThat(response.updatedBy()).isNull();
	}

	@Test
	void updatesThresholdsAndWritesStructuredAuditWithoutSecrets() {
		when(repository.findById(XaiAlertConfig.SINGLETON_ID))
			.thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(invocation ->
			invocation.getArgument(0)
		);
		Logger logger = (Logger) LoggerFactory.getLogger(
			XaiAlertConfigService.class
		);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			var response = service.update(
				99L,
				new UpdateXaiAlertsRequest(
					new BigDecimal("20"),
					new BigDecimal("80"),
					5,
					20
				)
			);

			assertThat(response.balanceCriticalUsd())
				.isEqualByComparingTo("20");
			assertThat(response.updatedBy()).isEqualTo(99L);
			assertThat(response.updatedAt()).isEqualTo(NOW);
			Map<String, Object> fields = appender.list.getLast()
				.getKeyValuePairs().stream()
				.collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
			assertThat(fields)
				.containsEntry("action", "XAI_ALERT_UPDATED")
				.containsEntry("actorUserId", 99L)
				.containsKeys("before", "after", "occurredAt");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void rejectsNonPositiveOrReversedThresholds() {
		assertThatThrownBy(() -> service.update(
			1L,
			new UpdateXaiAlertsRequest(
				new BigDecimal("50"),
				new BigDecimal("10"),
				30,
				7
			)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
	}

	@Test
	void rejectsMissingDayThresholdsAtTheServiceBoundary() {
		assertThatThrownBy(() -> service.update(
			1L,
			new UpdateXaiAlertsRequest(
				new BigDecimal("10"),
				new BigDecimal("50"),
				null,
				30
			)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
	}
}
