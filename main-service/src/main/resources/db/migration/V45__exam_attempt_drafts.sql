CREATE TABLE exam_attempt_drafts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exam_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    answers JSON NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_attempt_drafts_exam
        FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT fk_exam_attempt_drafts_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_exam_attempt_drafts_exam_user UNIQUE (exam_id, user_id)
);

CREATE INDEX idx_exam_attempt_drafts_updated_at
    ON exam_attempt_drafts (updated_at);
