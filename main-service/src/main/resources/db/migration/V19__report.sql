CREATE TABLE report_criteria (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    criterion_key VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    rubric_json JSON NOT NULL,
    allowed_sources_json JSON NOT NULL,
    min_evidence INT NOT NULL DEFAULT 2,
    weight DECIMAL(5, 2) NOT NULL,
    version INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_report_criteria_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT uk_report_criteria_classroom_key_version
        UNIQUE (classroom_id, criterion_key, version),
    CONSTRAINT chk_report_criteria_min_evidence CHECK (min_evidence >= 1),
    CONSTRAINT chk_report_criteria_weight CHECK (weight > 0),
    CONSTRAINT chk_report_criteria_version CHECK (version >= 1),
    INDEX idx_report_criteria_classroom_active (classroom_id, active)
);

CREATE TABLE report_generations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    request_id VARCHAR(255) NOT NULL,
    scope_type VARCHAR(20) NOT NULL,
    week_number INT NULL,
    scope_hash VARCHAR(64) NOT NULL,
    snapshot_hash VARCHAR(64) NULL,
    criterion_catalog_json JSON NULL,
    policy_version VARCHAR(20) NOT NULL,
    source_data_as_of DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL,
    failure_code VARCHAR(50) NULL,
    model VARCHAR(100) NULL,
    prompt_version VARCHAR(20) NULL,
    generation_lease_token VARCHAR(36) NULL,
    generation_lease_until DATETIME(6) NOT NULL
        DEFAULT '1970-01-01 00:00:00.000000',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_report_generations_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT fk_report_generations_student
        FOREIGN KEY (student_id) REFERENCES users (id),
    CONSTRAINT fk_report_generations_requested_by
        FOREIGN KEY (requested_by) REFERENCES users (id),
    CONSTRAINT uk_report_generations_classroom_student_request
        UNIQUE (classroom_id, student_id, request_id),
    CONSTRAINT chk_report_generations_scope_type
        CHECK (scope_type IN ('FULL', 'WEEK')),
    CONSTRAINT chk_report_generations_week_number
        CHECK (week_number IS NULL OR week_number >= 1),
    CONSTRAINT chk_report_generations_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    INDEX idx_report_generations_status_lease (status, generation_lease_until),
    INDEX idx_report_generations_classroom_student_status
        (classroom_id, student_id, status)
);

CREATE TABLE student_reports (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_id BIGINT NOT NULL,
    classroom_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    version INT NOT NULL,
    previous_report_id BIGINT NULL,
    overall_score DECIMAL(5, 2) NULL,
    overall_stage VARCHAR(20) NULL,
    summary TEXT NULL,
    data_quality_json JSON NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_student_reports_generation
        FOREIGN KEY (generation_id) REFERENCES report_generations (id),
    CONSTRAINT fk_student_reports_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT fk_student_reports_student
        FOREIGN KEY (student_id) REFERENCES users (id),
    CONSTRAINT fk_student_reports_previous_report
        FOREIGN KEY (previous_report_id) REFERENCES student_reports (id),
    CONSTRAINT uk_student_reports_generation UNIQUE (generation_id),
    CONSTRAINT uk_student_reports_classroom_student_version
        UNIQUE (classroom_id, student_id, version),
    CONSTRAINT chk_student_reports_version CHECK (version >= 1),
    CONSTRAINT chk_student_reports_overall_score CHECK (
        overall_score IS NULL OR (overall_score >= 0 AND overall_score <= 100)
    )
);

CREATE TABLE report_criterion_results (
    id BIGINT NOT NULL AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    criterion_key VARCHAR(50) NOT NULL,
    criterion_version INT NOT NULL,
    score DECIMAL(5, 2) NULL,
    trend VARCHAR(10) NULL,
    status VARCHAR(30) NOT NULL,
    narrative TEXT NULL,
    evidence_ids_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_report_criterion_results_report
        FOREIGN KEY (report_id) REFERENCES student_reports (id),
    CONSTRAINT uk_report_criterion_results_report_key
        UNIQUE (report_id, criterion_key),
    CONSTRAINT chk_report_criterion_results_score CHECK (
        score IS NULL OR (score >= 0 AND score <= 100)
    ),
    CONSTRAINT chk_report_criterion_results_trend CHECK (
        trend IS NULL OR trend IN ('UP', 'FLAT', 'DOWN')
    ),
    CONSTRAINT chk_report_criterion_results_status CHECK (
        status IN ('ASSESSED', 'INSUFFICIENT_DATA')
    ),
    CONSTRAINT chk_report_criterion_results_assessed_score CHECK (
        status <> 'ASSESSED' OR score IS NOT NULL
    )
);

CREATE TABLE report_evidence_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    generation_id BIGINT NOT NULL,
    evidence_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_ref VARCHAR(255) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    public_label VARCHAR(255) NOT NULL,
    minimal_fact_json JSON NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_report_evidence_snapshots_generation
        FOREIGN KEY (generation_id) REFERENCES report_generations (id),
    CONSTRAINT uk_report_evidence_snapshots_generation_evidence
        UNIQUE (generation_id, evidence_id),
    INDEX idx_report_evidence_snapshots_generation_source
        (generation_id, source_type)
);
