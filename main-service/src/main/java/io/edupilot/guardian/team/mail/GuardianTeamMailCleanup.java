package io.edupilot.guardian.team.mail;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.mail.EmailDelivery;
import io.edupilot.mail.EmailDeliveryRepository;
import io.edupilot.mail.EmailDeliveryType;
import io.edupilot.mail.EmailOutbox;
import io.edupilot.mail.EmailOutboxRepository;

/**
 * 요청 상태 변경·연락처 정리와 같은 트랜잭션에서 내부 안내 사본만 정리합니다.
 * 요청을 잠근 서비스에서 호출해야 하며, 메일함·제공자·백업의 삭제를 의미하지 않습니다.
 */
@Service
public class GuardianTeamMailCleanup {
	private final GuardianTeamMailBindingRepository bindings;
	private final EmailOutboxRepository outbox;
	private final EmailDeliveryRepository deliveries;

	GuardianTeamMailCleanup(GuardianTeamMailBindingRepository bindings, EmailOutboxRepository outbox,
		EmailDeliveryRepository deliveries) {
		this.bindings = bindings;
		this.outbox = outbox;
		this.deliveries = deliveries;
	}

	/** 같은 신청을 여러 번 정리해도 무관한 메일이나 전역 발송량 기록은 변경하지 않습니다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void purgeForRequest(String requestId) {
		GuardianTeamMailBinding.validateRequestId(requestId);
		for (GuardianTeamMailBinding binding : bindings.findForRequestUpdate(requestId)) {
			// worker와 같은 순서로 잠급니다. 이미 시작된 외부 호출 자체를 회수하지는 못합니다.
			EmailOutbox job = outbox.findForUpdate(binding.deliveryId()).orElse(null);
			EmailDelivery delivery = deliveries.findForUpdate(binding.deliveryId())
				.orElseThrow(() -> new IllegalStateException("보호자 안내 사본의 연결 상태를 확인할 수 없습니다."));
			if (delivery.getType() != EmailDeliveryType.GUARDIAN_TEAM_NOTICE) {
				throw new IllegalStateException("보호자 안내와 다른 용도의 메일이 연결되어 있습니다.");
			}
			if (job != null) {
				job.eraseGuardianPayload();
			}
			delivery.eraseGuardianContact();
			// 사본이 정리되면 사용자 요청과 발송량 잔여 기록의 연결도 남기지 않습니다.
			bindings.delete(binding);
		}
	}
}
