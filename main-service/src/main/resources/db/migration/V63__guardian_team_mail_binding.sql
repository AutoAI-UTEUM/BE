-- 보호자 안내 사본을 다른 메일과 구분합니다. 일반 저장·발송은 애플리케이션에서 차단합니다.
-- 이 migration은 기존 수신자·본문을 복사하거나 운영 데이터를 파기하지 않습니다.
ALTER TABLE email_deliveries DROP CHECK chk_email_deliveries_type;
ALTER TABLE email_deliveries ADD CONSTRAINT chk_email_deliveries_type CHECK (type IN (
    'PASSWORD_RESET', 'EMAIL_VERIFY', 'NOTIFICATION', 'TEST', 'GUARDIAN_TEAM_NOTICE'
));

-- delivery_id 기본키가 사본 하나의 중복·다른 신청 연결을 차단합니다.
-- 최초 수집일·파기 기한을 새로 저장하지 않으므로 재발급으로 기간을 연장하지 않습니다.
-- 실제 발송과 바인딩 생성 API는 제공하지 않습니다.
CREATE TABLE guardian_team_mail_bindings (
    delivery_id BIGINT NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    bound_at DATETIME(6) NOT NULL,
    PRIMARY KEY (delivery_id),
    INDEX idx_guardian_team_mail_request (request_id, delivery_id),
    CONSTRAINT fk_guardian_team_mail_delivery FOREIGN KEY (delivery_id)
        REFERENCES email_deliveries(id) ON DELETE CASCADE,
    CONSTRAINT fk_guardian_team_mail_request FOREIGN KEY (request_id)
        REFERENCES guardian_team_requests(id) ON DELETE RESTRICT
);
