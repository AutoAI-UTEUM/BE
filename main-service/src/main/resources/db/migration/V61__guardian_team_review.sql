-- 유료 업체·문자 발송 없이 팀 담당자가 최종 판단하는 별도 신청 원장입니다.
-- 적용은 스키마 추가만 수행하며 기존 계정 자격·권한·보존 정책을 변경하지 않습니다.
CREATE TABLE guardian_team_requests (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(30) NOT NULL,
    notice_version VARCHAR(100) NOT NULL,
    notice_digest VARCHAR(64) NOT NULL,
    configuration_digest VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    generation_started_at DATETIME(6) NOT NULL,
    request_expires_at DATETIME(6) NOT NULL,
    token_hash VARCHAR(64) NULL,
    consumed_token_hash VARCHAR(64) NULL,
    token_expires_at DATETIME(6) NULL,
    guardian_name VARCHAR(100) NULL,
    guardian_contact VARCHAR(254) NULL,
    contact_origin VARCHAR(20) NOT NULL,
    first_collected_at DATETIME(6) NULL,
    unconfirmed_erase_due_at DATETIME(6) NULL,
    web_declared_at DATETIME(6) NULL,
    relationship VARCHAR(25) NULL,
    declared_scopes VARCHAR(100) NULL,
    explicit_response_at DATETIME(6) NULL,
    confirmation_method VARCHAR(20) NULL,
    evidence_reference VARCHAR(100) NULL,
    confirmed_by BIGINT NULL,
    relationship_checked BOOLEAN NOT NULL,
    legal_method_checked BOOLEAN NOT NULL,
    approved_at DATETIME(6) NULL,
    approved_until DATETIME(6) NULL,
    approved_by BIGINT NULL,
    evidence_erase_due_at DATETIME(6) NULL,
    ended_at DATETIME(6) NULL,
    reason_code VARCHAR(40) NULL,
    evidence_erased_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_guardian_team_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_guardian_team_current_user UNIQUE (user_id),
    CONSTRAINT uk_guardian_team_token UNIQUE (token_hash),
    CONSTRAINT chk_guardian_team_generation CHECK (generation > 0 AND revision > 0),
    CONSTRAINT chk_guardian_team_state CHECK (state IN ('AWAITING_CONSENT', 'DECLARED', 'REVIEW_PENDING', 'NEEDS_INFORMATION', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED', 'WITHDRAWN')),
    CONSTRAINT chk_guardian_team_origin CHECK (contact_origin IN ('NONE', 'CHILD', 'GUARDIAN')),
    CONSTRAINT chk_guardian_team_collection CHECK (
        (first_collected_at IS NULL AND unconfirmed_erase_due_at IS NULL)
        OR (first_collected_at IS NOT NULL AND unconfirmed_erase_due_at IS NOT NULL
            AND unconfirmed_erase_due_at <= DATE_ADD(first_collected_at, INTERVAL 5 DAY))),
    CONSTRAINT chk_guardian_team_approval CHECK (state <> 'APPROVED'
        OR (approved_at IS NOT NULL AND approved_until IS NOT NULL AND evidence_erase_due_at IS NOT NULL))
);
CREATE INDEX ix_guardian_team_expiry ON guardian_team_requests (state, request_expires_at);
CREATE INDEX ix_guardian_team_contact_due ON guardian_team_requests (unconfirmed_erase_due_at);
CREATE INDEX ix_guardian_team_evidence_due ON guardian_team_requests (evidence_erase_due_at);
CREATE INDEX ix_guardian_team_consumed_token ON guardian_team_requests (consumed_token_hash);

CREATE TABLE guardian_team_events (
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    generation BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    state VARCHAR(30) NOT NULL,
    actor_id BIGINT NULL,
    recorded_at DATETIME(6) NOT NULL,
    erase_due_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_guardian_team_event_request FOREIGN KEY (request_id) REFERENCES guardian_team_requests(id)
);
CREATE INDEX ix_guardian_team_event_order ON guardian_team_events (request_id, recorded_at, id);
CREATE INDEX ix_guardian_team_event_due ON guardian_team_events (erase_due_at, request_id);

CREATE TABLE guardian_team_operations (
    id VARCHAR(36) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    operation_key VARCHAR(100) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    erase_due_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_guardian_team_operation_request FOREIGN KEY (request_id) REFERENCES guardian_team_requests(id),
    CONSTRAINT uk_guardian_team_operation UNIQUE (request_id, operation_key)
);
CREATE INDEX ix_guardian_team_operation_due ON guardian_team_operations (request_id, erase_due_at);
