package io.edupilot.admin.xai;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.admin.xai.dto.AdminXaiAlertsResponse;
import io.edupilot.admin.xai.dto.UpdateXaiAlertsRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

@Service
public class XaiAlertConfigService {


	private final XaiAlertConfigRepository repository;
	private final Clock clock;

	public XaiAlertConfigService(
		XaiAlertConfigRepository repository,
		Clock clock
	) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AdminXaiAlertsResponse get() {
		return repository.findById(XaiAlertConfig.SINGLETON_ID)
			.map(this::response)
			.orElseGet(this::defaultResponse);
	}

	@Transactional(readOnly = true)
	public XaiAlertThresholds currentThresholds() {
		return repository.findById(XaiAlertConfig.SINGLETON_ID)
			.map(XaiAlertConfig::thresholds)
			.orElseGet(XaiAlertThresholds::defaults);
	}

	@Transactional
	public UpdateResult update(
		Long actorUserId,
		UpdateXaiAlertsRequest request
	) {
		if (request.depletionCriticalDays() == null
			|| request.depletionWarningDays() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		XaiAlertThresholds after = new XaiAlertThresholds(
			request.balanceCriticalUsd(),
			request.balanceWarningUsd(),
			request.depletionCriticalDays(),
			request.depletionWarningDays()
		);
		validate(after);
		XaiAlertConfig config = repository.findById(
			XaiAlertConfig.SINGLETON_ID
		).orElseGet(() -> XaiAlertConfig.defaults(clock.instant()));
		XaiAlertThresholds before = config.thresholds();
		config.apply(after, actorUserId, clock.instant());
		XaiAlertConfig saved = repository.save(config);
		return new UpdateResult(response(saved), before, after);
	}

	public record UpdateResult(
		AdminXaiAlertsResponse response,
		XaiAlertThresholds before,
		XaiAlertThresholds after
	) {
	}

	private void validate(XaiAlertThresholds thresholds) {
		if (thresholds.balanceCriticalUsd() == null
			|| thresholds.balanceWarningUsd() == null
			|| thresholds.balanceCriticalUsd().signum() <= 0
			|| thresholds.balanceWarningUsd().signum() <= 0
			|| thresholds.balanceCriticalUsd().compareTo(
				thresholds.balanceWarningUsd()
			) >= 0
			|| thresholds.depletionCriticalDays() <= 0
			|| thresholds.depletionWarningDays() <= 0
			|| thresholds.depletionCriticalDays()
				>= thresholds.depletionWarningDays()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private AdminXaiAlertsResponse defaultResponse() {
		XaiAlertThresholds defaults = XaiAlertThresholds.defaults();
		return new AdminXaiAlertsResponse(
			defaults.balanceCriticalUsd(),
			defaults.balanceWarningUsd(),
			defaults.depletionCriticalDays(),
			defaults.depletionWarningDays(),
			null,
			null
		);
	}

	private AdminXaiAlertsResponse response(XaiAlertConfig config) {
		XaiAlertThresholds thresholds = config.thresholds();
		return new AdminXaiAlertsResponse(
			thresholds.balanceCriticalUsd(),
			thresholds.balanceWarningUsd(),
			thresholds.depletionCriticalDays(),
			thresholds.depletionWarningDays(),
			config.getUpdatedBy(),
			config.getUpdatedAt()
		);
	}
}
