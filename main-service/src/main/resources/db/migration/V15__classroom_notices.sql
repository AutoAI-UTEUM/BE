CREATE TABLE classroom_notices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classroom_notices_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    INDEX idx_classroom_notices_classroom_published (
        classroom_id, published_at, id
    )
);
