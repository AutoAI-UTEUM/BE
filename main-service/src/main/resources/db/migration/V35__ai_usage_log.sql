CREATE TABLE ai_usage_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    feature VARCHAR(40) NOT NULL,
    model VARCHAR(50) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    reasoning_tokens BIGINT NULL,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_ai_usage_user_day (user_id, created_at),
    INDEX idx_ai_usage_feature (feature, created_at)
);
