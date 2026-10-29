-- Intake only: no updates to users.date_of_birth, access cohorts or verification evidence.
CREATE TABLE birthdate_correction_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    requested_date_of_birth DATE NULL,
    state VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_birthdate_correction_user UNIQUE (user_id),
    CONSTRAINT fk_birthdate_correction_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT ck_birthdate_correction_state CHECK (
        (state = 'PENDING' AND requested_date_of_birth IS NOT NULL)
        OR (state = 'WITHDRAWN' AND requested_date_of_birth IS NULL)
    ),
    INDEX ix_birthdate_correction_queue (state, requested_at, id)
);
