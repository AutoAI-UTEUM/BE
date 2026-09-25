CREATE TABLE policy_documents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type VARCHAR(20) NOT NULL,
    version VARCHAR(20) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    summary VARCHAR(1000) NULL,
    effective_at DATETIME(6) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_policy_documents_type CHECK (type IN ('TERMS', 'PRIVACY')),
    CONSTRAINT uk_policy_documents_type_version UNIQUE (type, version),
    INDEX idx_policy_documents_type_effective (type, effective_at, id)
);

CREATE TABLE policy_consents (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    policy_type VARCHAR(20) NOT NULL,
    policy_version VARCHAR(20) NOT NULL,
    agreed_at DATETIME(6) NOT NULL,
    ip VARCHAR(45) NOT NULL,
    user_agent VARCHAR(255) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_policy_consents_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_policy_consents_type CHECK (policy_type IN ('TERMS', 'PRIVACY')),
    CONSTRAINT uk_policy_consents_user_type_version
        UNIQUE (user_id, policy_type, policy_version),
    INDEX idx_policy_consents_user_type (user_id, policy_type)
);

-- System seed (created_by=0). The text is a legal-review placeholder, not approved policy language.
INSERT INTO policy_documents
    (type, version, title, content, summary, effective_at, created_by, created_at)
VALUES
    ('TERMS', '0.9', '이용약관 0.9 — 법무 검토 전 초안',
     '[법무 검토 전 초안] 서비스, 계정, AI 생성물, 금지 행위 및 책임 범위는 docs/policy-draft.md를 검토해 확정해야 합니다.',
     '최초 검토용 초안', CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6)),
    ('PRIVACY', '0.9', '개인정보처리방침 0.9 — 법무 검토 전 초안',
     '[법무 검토 전 초안] 수집 항목, xAI 국외 전달, 보관·파기와 권리행사 절차는 docs/policy-draft.md를 검토해 확정해야 합니다.',
     '최초 검토용 초안', CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6));
