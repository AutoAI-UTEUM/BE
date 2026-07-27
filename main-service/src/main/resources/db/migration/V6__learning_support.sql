CREATE TABLE quiz_assessments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    quiz_submission_id BIGINT NOT NULL,
    assessment_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_quiz_assessments_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT fk_quiz_assessments_submission
        FOREIGN KEY (quiz_submission_id) REFERENCES quiz_submissions (id),
    CONSTRAINT uk_quiz_assessments_submission
        UNIQUE (quiz_submission_id),
    INDEX idx_quiz_assessments_session_created
        (session_id, created_at)
);

CREATE TABLE diagnoses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    quiz_submission_id BIGINT NOT NULL,
    diagnostic_prompt TEXT NOT NULL,
    user_answer TEXT NULL,
    diagnosis_result_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_diagnoses_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT fk_diagnoses_submission
        FOREIGN KEY (quiz_submission_id) REFERENCES quiz_submissions (id),
    CONSTRAINT uk_diagnoses_submission
        UNIQUE (quiz_submission_id),
    CONSTRAINT chk_diagnoses_status
        CHECK (status IN ('PENDING', 'ANSWERED', 'COMPLETED')),
    INDEX idx_diagnoses_session_status
        (session_id, status)
);

CREATE TABLE repair_results (
    id BIGINT NOT NULL AUTO_INCREMENT,
    diagnosis_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    repair_content MEDIUMTEXT NOT NULL,
    repair_result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_repair_results_diagnosis
        FOREIGN KEY (diagnosis_id) REFERENCES diagnoses (id),
    CONSTRAINT fk_repair_results_session
        FOREIGN KEY (session_id) REFERENCES learning_sessions (id),
    CONSTRAINT uk_repair_results_diagnosis
        UNIQUE (diagnosis_id)
);

CREATE TABLE learner_memories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    strengths_json JSON NOT NULL,
    weaknesses_json JSON NOT NULL,
    misconceptions_json JSON NOT NULL,
    explanation_preferences_json JSON NOT NULL,
    preferred_quiz_types_json JSON NOT NULL,
    target_difficulty VARCHAR(30) NULL,
    next_coaching_goals_json JSON NOT NULL,
    memory_digest TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_learner_memories_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_learner_memories_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT uk_learner_memories_user_material
        UNIQUE (user_id, material_id)
);

CREATE TABLE learner_memory_candidates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    candidate_type VARCHAR(30) NOT NULL,
    content TEXT NOT NULL,
    confidence DECIMAL(3, 2) NOT NULL,
    evidence_refs_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_memory_candidates_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_memory_candidates_material
        FOREIGN KEY (material_id) REFERENCES learning_materials (id),
    CONSTRAINT chk_memory_candidates_confidence
        CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT chk_memory_candidates_status
        CHECK (status IN ('CANDIDATE', 'PROMOTED', 'ARCHIVED')),
    INDEX idx_memory_candidates_user_material_status
        (user_id, material_id, status)
);

ALTER TABLE learning_sessions
    ADD COLUMN pending_diagnosis_id BIGINT NULL AFTER active_quiz_id;
