ALTER TABLE ai_usage_log
    ADD COLUMN cost_usd_ticks BIGINT NULL;

ALTER TABLE ai_usage_log
    ADD COLUMN request_id VARCHAR(64) NULL;

ALTER TABLE ai_usage_log
    ADD CONSTRAINT uk_ai_usage_log_request_id UNIQUE (request_id);

CREATE TABLE xai_alert_config (
    id TINYINT NOT NULL,
    balance_critical_usd DECIMAL(19, 4) NOT NULL,
    balance_warning_usd DECIMAL(19, 4) NOT NULL,
    daily_cost_warning_usd DECIMAL(19, 4) NULL,
    depletion_critical_days INT NOT NULL,
    depletion_warning_days INT NOT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT chk_xai_alert_config_single_row CHECK (id = 1),
    CONSTRAINT fk_xai_alert_config_updated_by FOREIGN KEY (updated_by)
        REFERENCES users (id)
);

INSERT INTO xai_alert_config (
    id,
    balance_critical_usd,
    balance_warning_usd,
    daily_cost_warning_usd,
    depletion_critical_days,
    depletion_warning_days,
    updated_by
) VALUES (1, 10.0000, 50.0000, NULL, 7, 30, NULL);
