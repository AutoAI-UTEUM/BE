CREATE TABLE classrooms (
    id BIGINT NOT NULL AUTO_INCREMENT,
    instructor_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    color VARCHAR(20) NOT NULL,
    description VARCHAR(255) NULL,
    status VARCHAR(20) NOT NULL,
    invite_code VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classrooms_instructor
        FOREIGN KEY (instructor_id) REFERENCES users (id),
    CONSTRAINT uk_classrooms_invite_code UNIQUE (invite_code),
    CONSTRAINT chk_classrooms_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_classrooms_color CHECK (
        color IN ('BLUE', 'GREEN', 'PURPLE', 'ORANGE', 'RED', 'GRAY')
    ),
    CONSTRAINT chk_classrooms_status CHECK (status IN ('ACTIVE', 'COMPLETED')),
    INDEX idx_classrooms_instructor_status_created (
        instructor_id, status, created_at, id
    )
);

CREATE TABLE classroom_members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classroom_members_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT fk_classroom_members_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_classroom_members_classroom_user
        UNIQUE (classroom_id, user_id),
    INDEX idx_classroom_members_user_classroom (user_id, classroom_id)
);

CREATE TABLE classroom_join_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    classroom_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_classroom_join_requests_classroom
        FOREIGN KEY (classroom_id) REFERENCES classrooms (id),
    CONSTRAINT fk_classroom_join_requests_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_classroom_join_requests_classroom_user
        UNIQUE (classroom_id, user_id),
    CONSTRAINT chk_classroom_join_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    INDEX idx_classroom_join_requests_classroom_status_requested (
        classroom_id, status, requested_at, id
    ),
    INDEX idx_classroom_join_requests_user_status_requested (
        user_id, status, requested_at, id
    )
);
