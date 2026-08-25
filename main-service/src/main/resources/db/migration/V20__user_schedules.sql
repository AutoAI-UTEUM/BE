CREATE TABLE user_schedules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    has_time BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_user_schedules_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_user_schedules_time_range CHECK (ends_at >= starts_at),
    INDEX idx_user_schedules_user_starts_at (user_id, starts_at)
);
