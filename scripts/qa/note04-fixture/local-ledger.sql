-- Local test schema only. NOT a Flyway migration or approved DEV DDL.
-- Install before fixture transactions: MySQL DDL has implicit commit semantics.
CREATE TABLE qa_fixture_runs (
    run_label VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    manifest_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    approved_scope_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    state VARCHAR(20) NOT NULL,
    manifest_sha256 CHAR(64) COLLATE utf8mb4_bin NULL,
    manifest_json JSON NULL,
    PRIMARY KEY (run_label),
    UNIQUE KEY uk_qa_fixture_manifest (manifest_id),
    CHECK (state IN ('BUILDING', 'COMMITTED', 'CLEANED'))
) ENGINE=InnoDB;

CREATE TABLE qa_fixture_run_resources (
    run_label VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    kind VARCHAR(20) NOT NULL,
    alias VARCHAR(20) NOT NULL,
    resource_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    session_id BIGINT NULL,
    disposition VARCHAR(20) NOT NULL,
    row_sha256 CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (run_label, kind, resource_id),
    UNIQUE KEY uk_qa_fixture_alias (run_label, kind, alias),
    FOREIGN KEY (run_label) REFERENCES qa_fixture_runs (run_label),
    CHECK (kind IN ('USER', 'MATERIAL', 'SESSION', 'QUIZ')),
    CHECK (disposition IN ('CREATED', 'REUSED_PROTECTED'))
) ENGINE=InnoDB;
