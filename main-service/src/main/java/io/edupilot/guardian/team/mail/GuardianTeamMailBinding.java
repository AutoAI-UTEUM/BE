package io.edupilot.guardian.team.mail;

import java.time.Instant;
import java.util.UUID;

import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 보호자 요청과 안내 사본의 내부 연결입니다. 외부 API나 일반 메일 발송에서 생성하지 않습니다.
 * 최초 수집일과 파기 기한은 요청에서 관리하며 여기에서 새 기간을 부여하지 않습니다.
 */
@Entity
@Table(name = "guardian_team_mail_bindings", indexes = {
	@Index(name = "idx_guardian_team_mail_request", columnList = "request_id, delivery_id")
})
class GuardianTeamMailBinding {
	@Id
	@Column(name = "delivery_id", nullable = false, updatable = false)
	private Long deliveryId;
	@Column(name = "request_id", nullable = false, length = 36, updatable = false)
	private String requestId;
	@Column(name = "bound_at", nullable = false, updatable = false)
	private Instant boundAt;

	protected GuardianTeamMailBinding() { }

	/** 현재는 합성 검증에서만 사용하며, 지원되는 발송·바인딩 생성 경로는 없습니다. */
	static GuardianTeamMailBinding trustedCopy(String requestId, EmailDelivery delivery, Instant now) {
		validateRequestId(requestId);
		if (delivery == null || delivery.getId() == null
			|| delivery.getType() != EmailDeliveryType.GUARDIAN_TEAM_NOTICE || now == null) {
			throw new IllegalArgumentException("보호자 요청에 연결할 수 있는 안내 사본이 아닙니다.");
		}
		GuardianTeamMailBinding binding = new GuardianTeamMailBinding();
		binding.deliveryId = delivery.getId();
		binding.requestId = requestId;
		binding.boundAt = now;
		return binding;
	}

	static void validateRequestId(String requestId) {
		if (requestId == null || requestId.length() != 36) {
			throw new IllegalArgumentException("보호자 신청 번호 형식이 올바르지 않습니다.");
		}
		try {
			if (!UUID.fromString(requestId).toString().equals(requestId)) {
				throw new IllegalArgumentException();
			}
		} catch (IllegalArgumentException invalid) {
			throw new IllegalArgumentException("보호자 신청 번호 형식이 올바르지 않습니다.");
		}
	}

	Long deliveryId() { return deliveryId; }
}
