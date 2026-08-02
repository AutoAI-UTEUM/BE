CREATE TABLE exams (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    week_number INT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    allow_retake BOOLEAN NOT NULL DEFAULT FALSE,
    total_score DECIMAL(10, 2) NOT NULL DEFAULT 0,
    published_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_exams_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT chk_exams_week_number
        CHECK (week_number IS NULL OR week_number >= 1),
    CONSTRAINT chk_exams_status
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    CONSTRAINT chk_exams_total_score CHECK (total_score >= 0),
    INDEX idx_exams_classroom_status (classroom_id, status)
);

CREATE TABLE exam_questions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exam_id BIGINT NOT NULL,
    question_no INT NOT NULL,
    question_type VARCHAR(20) NOT NULL,
    points DECIMAL(10, 2) NOT NULL,
    public_question_json JSON NOT NULL,
    private_answer_json JSON NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_questions_exam
        FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT uk_exam_questions_exam_question_no
        UNIQUE (exam_id, question_no),
    CONSTRAINT chk_exam_questions_question_no CHECK (question_no >= 1),
    CONSTRAINT chk_exam_questions_type
        CHECK (question_type IN ('MCQ', 'OX', 'SHORT', 'ESSAY')),
    CONSTRAINT chk_exam_questions_points CHECK (points > 0)
);

CREATE TABLE exam_submissions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exam_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    request_id VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    graded_at DATETIME(6) NULL,
    score DECIMAL(10, 2) NULL,
    max_score DECIMAL(10, 2) NOT NULL,
    normalized_score DECIMAL(10, 2) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_submissions_exam
        FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT fk_exam_submissions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_exam_submissions_exam_user_attempt
        UNIQUE (exam_id, user_id, attempt_no),
    CONSTRAINT uk_exam_submissions_exam_user_request
        UNIQUE (exam_id, user_id, request_id),
    CONSTRAINT chk_exam_submissions_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT chk_exam_submissions_status
        CHECK (status IN ('SUBMITTED', 'GRADED', 'GRADING_FAILED')),
    CONSTRAINT chk_exam_submissions_max_score CHECK (max_score > 0),
    CONSTRAINT chk_exam_submissions_score CHECK (
        score IS NULL OR (score >= 0 AND score <= max_score)
    ),
    CONSTRAINT chk_exam_submissions_normalized_score CHECK (
        normalized_score IS NULL
        OR (normalized_score >= 0 AND normalized_score <= 100)
    )
);

CREATE TABLE exam_answers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    answer TEXT NULL,
    score DECIMAL(10, 2) NULL,
    max_score DECIMAL(10, 2) NOT NULL,
    verdict VARCHAR(20) NULL,
    feedback TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_answers_submission
        FOREIGN KEY (submission_id) REFERENCES exam_submissions (id),
    CONSTRAINT fk_exam_answers_question
        FOREIGN KEY (question_id) REFERENCES exam_questions (id),
    CONSTRAINT uk_exam_answers_submission_question
        UNIQUE (submission_id, question_id),
    CONSTRAINT chk_exam_answers_max_score CHECK (max_score > 0),
    CONSTRAINT chk_exam_answers_score CHECK (
        score IS NULL OR (score >= 0 AND score <= max_score)
    ),
    CONSTRAINT chk_exam_answers_verdict CHECK (
        verdict IS NULL OR verdict IN ('CORRECT', 'PARTIAL', 'WRONG')
    )
);
