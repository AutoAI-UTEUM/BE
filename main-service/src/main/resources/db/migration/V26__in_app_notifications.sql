CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    link_json JSON NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_notifications_type CHECK (
        type IN (
            'MATERIAL_UPLOADED',
            'NOTICE_PUBLISHED',
            'JOIN_REQUEST_RECEIVED',
            'JOIN_REQUEST_PROCESSED'
        )
    ),
    INDEX idx_notifications_user_created (user_id, created_at)
);

ALTER TABLE classroom_notices
    ADD COLUMN notification_sent_at DATETIME(6) NULL AFTER publish_at;

UPDATE classroom_notices
SET notification_sent_at = COALESCE(publish_at, published_at)
WHERE publish_at IS NULL OR publish_at <= UTC_TIMESTAMP(6);
