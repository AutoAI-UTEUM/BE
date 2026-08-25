CREATE TABLE classroom_resource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    type VARCHAR(10) NOT NULL,
    title VARCHAR(200) NOT NULL,
    week_number INT NULL,
    file_name VARCHAR(255) NULL,
    content_type VARCHAR(255) NULL,
    size_bytes BIGINT NULL,
    storage_path VARCHAR(255) NULL,
    url VARCHAR(2048) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classroom_resource_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT chk_classroom_resource_type
        CHECK (type IN ('FILE', 'LINK')),
    CONSTRAINT chk_classroom_resource_week_number
        CHECK (week_number IS NULL OR week_number >= 1),
    CONSTRAINT chk_classroom_resource_size
        CHECK (size_bytes IS NULL OR size_bytes > 0),
    CONSTRAINT chk_classroom_resource_metadata
        CHECK (
            (type = 'FILE'
                AND file_name IS NOT NULL
                AND size_bytes IS NOT NULL
                AND storage_path IS NOT NULL
                AND url IS NULL)
            OR
            (type = 'LINK'
                AND file_name IS NULL
                AND content_type IS NULL
                AND size_bytes IS NULL
                AND storage_path IS NULL
                AND url IS NOT NULL)
        ),
    INDEX idx_classroom_resource_classroom_week_created (
        classroom_id, week_number, created_at, id
    )
);
