package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "xai_alert_config")
public class XaiAlertConfig {

	static final byte SINGLETON_ID = 1;

	@Id
	private Byte id;

	@Column(name = "balance_critical_usd", nullable = false, precision = 19, scale = 4)
	private BigDecimal balanceCriticalUsd;

	@Column(name = "balance_warning_usd", nullable = false, precision = 19, scale = 4)
	private BigDecimal balanceWarningUsd;

	@Column(name = "daily_cost_warning_usd", precision = 19, scale = 4)
	private BigDecimal dailyCostWarningUsd;

	@Column(name = "depletion_critical_days", nullable = false)
	private Integer depletionCriticalDays;

	@Column(name = "depletion_warning_days", nullable = false)
	private Integer depletionWarningDays;

	@Column(name = "updated_by")
	private Long updatedBy;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected XaiAlertConfig() {
	}

	private XaiAlertConfig(XaiAlertThresholds thresholds, Instant now) {
		this.id = SINGLETON_ID;
		this.dailyCostWarningUsd = null;
		apply(thresholds, null, now);
	}

	static XaiAlertConfig defaults(Instant now) {
		return new XaiAlertConfig(XaiAlertThresholds.defaults(), now);
	}

	void apply(XaiAlertThresholds thresholds, Long actorUserId, Instant now) {
		this.balanceCriticalUsd = thresholds.balanceCriticalUsd();
		this.balanceWarningUsd = thresholds.balanceWarningUsd();
		this.depletionCriticalDays = thresholds.depletionCriticalDays();
		this.depletionWarningDays = thresholds.depletionWarningDays();
		this.updatedBy = actorUserId;
		this.updatedAt = now;
	}

	XaiAlertThresholds thresholds() {
		return new XaiAlertThresholds(
			balanceCriticalUsd,
			balanceWarningUsd,
			depletionCriticalDays,
			depletionWarningDays
		);
	}

	public BigDecimal getDailyCostWarningUsd() {
		return dailyCostWarningUsd;
	}

	public Long getUpdatedBy() {
		return updatedBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
