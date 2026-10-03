package io.edupilot.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailDeliveryStore {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final int HOURLY_RECIPIENT_LIMIT = 5;
	private static final int DAILY_GLOBAL_LIMIT = 500;

	private final EmailDeliveryRepository repository;
	private final EmailQuotaLockRepository locks;
	private final EmailSendReservationRepository reservations;
	private final Clock clock;

	public EmailDeliveryStore(EmailDeliveryRepository repository, EmailQuotaLockRepository locks,
		EmailSendReservationRepository reservations, Clock clock) {
		this.repository = repository;
		this.locks = locks;
		this.reservations = reservations;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Long queue(EmailMessage message) {
		return repository.saveAndFlush(EmailDelivery.queued(message, clock.instant())).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public boolean reserve(Long id, String claimToken) {
		if (claimToken == null || claimToken.isBlank() || claimToken.length() > 36) {
			throw new IllegalArgumentException("Invalid mail reservation claim");
		}
		// Always acquire the global row first; only this short DB transaction is serialized.
		// A missing migration-created row fails closed instead of bypassing the quota.
		locks.findForUpdate(1).orElseThrow(() -> new IllegalStateException("Mail quota state unavailable"));
		EmailDelivery delivery = repository.findForUpdate(id).orElseThrow();
		if (delivery.getStatus() != EmailDeliveryStatus.QUEUED) {
			return false;
		}
		if (reservations.existsByDeliveryIdAndClaimToken(id, claimToken)) {
			return true;
		}
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Instant dayStart = now.atZone(KST).toLocalDate().atStartOfDay(KST).toInstant();
		long hourly = reservations.countRecipientReservations(
			delivery.getRecipient(), now.minusSeconds(3600)
		);
		long daily = reservations.countDailyReservations(dayStart);
		if (hourly >= HOURLY_RECIPIENT_LIMIT || daily >= DAILY_GLOBAL_LIMIT) {
			delivery.rateLimited();
			return false;
		}
		reservations.saveAndFlush(EmailSendReservation.reserved(delivery, claimToken, now));
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
