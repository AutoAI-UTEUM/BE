CREATE TABLE IF NOT EXISTS learning_sessions (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS classroom_members (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    classroom_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS session_page_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    explained_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_session_page_records_session_page
        UNIQUE (session_id, page_number)
);
