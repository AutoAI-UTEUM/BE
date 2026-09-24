package io.edupilot.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailDeliveryStore {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final int HOURLY_RECIPIENT_LIMIT = 5;
	private static final int DAILY_GLOBAL_LIMIT = 500;

	private final EmailDeliveryRepository repository;
	private final Clock clock;

	public EmailDeliveryStore(EmailDeliveryRepository repository, Clock clock) {
		this.repository = repository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long queue(EmailMessage message) {
		return repository.saveAndFlush(EmailDelivery.queued(message, clock.instant())).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean reserve(Long id) {
		EmailDelivery delivery = repository.findById(id).orElseThrow();
		Instant now = clock.instant();
		Instant dayStart = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
		long hourly = repository.countRecipientQuota(
			delivery.getRecipient(), now.minusSeconds(3600), id
		);
		long daily = repository.countDailyQuota(dayStart, id);
		if (hourly > HOURLY_RECIPIENT_LIMIT || daily > DAILY_GLOBAL_LIMIT) {
			delivery.rateLimited();
			return false;
		}
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void attempt(Long id) {
		repository.findById(id).orElseThrow().attempt();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void sent(Long id, String providerMessageId) {
		repository.findById(id).orElseThrow().sent(providerMessageId, clock.instant());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failed(Long id, String summary) {
		repository.findById(id).orElseThrow().failed(summary);
	}
}
