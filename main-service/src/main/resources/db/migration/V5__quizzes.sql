CREATE TABLE quizzes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    coverage_start_page INT NOT NULL,
    coverage_end_page INT NOT NULL,
    quiz_type VARCHAR(20) NOT NULL,
    public_question_json JSON NOT NULL,
    private_answer_json JSON NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_quizzes_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT chk_quizzes_page_number
        CHECK (page_number >= 1),
    CONSTRAINT chk_quizzes_coverage
        CHECK (
            coverage_start_page >= 1
            AND coverage_end_page >= coverage_start_page
        ),
    CONSTRAINT chk_quizzes_type
        CHECK (quiz_type IN ('MCQ', 'OX', 'SHORT', 'ESSAY')),
    INDEX idx_quizzes_session_created (session_id, created_at)
);

CREATE TABLE quiz_submissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    quiz_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    attempt_no INT NOT NULL DEFAULT 1,
    request_id VARCHAR(255) NOT NULL,
    submitted_answer_json JSON NOT NULL,
    score DECIMAL(10, 2) NOT NULL,
    max_score DECIMAL(10, 2) NOT NULL,
    passed BOOLEAN NOT NULL,
    grading_result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_quiz_submissions_quiz
        FOREIGN KEY (quiz_id) REFERENCES quizzes (id),
    CONSTRAINT fk_quiz_submissions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_quiz_submissions_attempt
        UNIQUE (quiz_id, user_id, attempt_no),
    CONSTRAINT uk_quiz_submissions_request
        UNIQUE (quiz_id, user_id, request_id),
    CONSTRAINT chk_quiz_submissions_score
        CHECK (score >= 0),
    CONSTRAINT chk_quiz_submissions_max_score
        CHECK (max_score > 0),
    CONSTRAINT chk_quiz_submissions_score_range
        CHECK (score <= max_score)
);
