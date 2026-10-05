package io.edupilot.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;

@Service
public class EmailOutboxStore {
	private final EmailOutboxRepository jobs;
	private final EmailDeliveryRepository deliveries;
	private final EmailPayloadCipher cipher;
	private final EmailOutboxProperties properties;
	private final EmailDispatchProperties dispatch;
	private final Clock clock;
	private final EntityManager entities;

	public EmailOutboxStore(EmailOutboxRepository jobs, EmailDeliveryRepository deliveries,
		EmailPayloadCipher cipher, EmailOutboxProperties properties, EmailDispatchProperties dispatch,
		Clock clock, EntityManager entities) {
		this.jobs = jobs;
		this.deliveries = deliveries;
		this.cipher = cipher;
		this.properties = properties;
		this.dispatch = dispatch;
		this.clock = clock;
		this.entities = entities;
	}

	/** Joins the caller transaction, so rolled-back token/account changes cannot leave sendable mail. */
	@Transactional
	public void enqueue(Long id, EmailMessage message, Instant expiry) {
		// The delivery ID is assigned before insertion. Persist this new row explicitly,
		// rather than having repository.save merge a shared-primary-key association.
		entities.persist(EmailOutbox.queued(deliveries.getReferenceById(id),
			cipher.encrypt(id, message), clock.instant().truncatedTo(ChronoUnit.MICROS), expiry));
		entities.flush();
	}

	@Transactional(readOnly = true)
	public List<Long> cleanupIds() {
		return jobs.findCleanupIds(clock.instant(), PageRequest.of(0, properties.batchSize()));
	}

	@Transactional(readOnly = true)
	public List<Long> dispatchableIds() {
		if (dispatch.mode() == EmailDispatchProperties.Mode.PAUSED) {
			return List.of();
		}
		if (dispatch.mode() == EmailDispatchProperties.Mode.ISOLATED_TRIAL) {
			return jobs.findTrialDispatchableIds(clock.instant(), dispatch.deliveryIds(), dispatch.recipient(),
				PageRequest.of(0, properties.batchSize()));
		}
		return jobs.findDispatchableIds(clock.instant(), PageRequest.of(0, properties.batchSize()));
	}

	public boolean dispatchBlocked(Long id) { return dispatch.blocks(id); }

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recover(Long id) {
		EmailOutbox job = jobs.findForUpdate(id).orElse(null);
		if (job != null) {
			recover(job, clock.instant());
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Claim claim(Long id) {
		if (dispatch.blocks(id)) {
			return null;
		}
		EmailOutbox job = jobs.findForUpdate(id).orElse(null);
		Instant now = clock.instant();
		if (job == null || !dispatch.permits(job)) {
			return null;
		}
		recover(job, now);
		if ((job.getStatus() != EmailOutboxStatus.READY && job.getStatus() != EmailOutboxStatus.RETRY)
			|| job.nextAttemptAt().isAfter(now)) {
			return null;
		}
		if (job.delivery().getStatus() != EmailDeliveryStatus.QUEUED) {
			job.terminal(job.delivery().getStatus() == EmailDeliveryStatus.SENT
				? EmailOutboxStatus.SENT : EmailOutboxStatus.FAILED, "DELIVERY_NOT_QUEUED");
			return null;
		}
		EmailMessage message;
		try {
			message = cipher.decrypt(id, job.payload());
		} catch (RuntimeException failure) {
			fail(job, EmailOutboxStatus.FAILED, "PAYLOAD_UNREADABLE");
			return null;
		}
		String token = UUID.randomUUID().toString();
		Claim claim = new Claim(id, token, message);
		if (!dispatch.permits(claim)) {
			return null;
		}
		job.claim(token, now.plus(properties.leaseDuration()));
		return claim;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean beginSending(Claim claim) {
		if (!dispatch.permits(claim)) {
			return false;
		}
		EmailOutbox job = jobs.findForUpdate(claim.id()).orElseThrow();
		Instant now = clock.instant();
		if (!dispatch.permits(job) || !job.ownedBy(claim.token()) || job.getStatus() != EmailOutboxStatus.CLAIMED
			|| !job.leaseUntil().isAfter(now)) {
			return false;
		}
		if (!job.expiresAt().isAfter(now)) {
			fail(job, EmailOutboxStatus.FAILED, "PAYLOAD_EXPIRED");
			return false;
		}
		if (job.delivery().getStatus() != EmailDeliveryStatus.QUEUED) {
			job.terminal(EmailOutboxStatus.FAILED, "DELIVERY_NOT_QUEUED");
			return false;
		}
		job.beginSending();
		job.delivery().attempt();
		return true;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void sent(Claim claim, String receipt) {
		EmailOutbox job = jobs.findForUpdate(claim.id()).orElseThrow();
		if (job.ownedBy(claim.token()) && (job.getStatus() == EmailOutboxStatus.SENDING
			|| job.getStatus() == EmailOutboxStatus.UNKNOWN)) {
			job.delivery().sent(receipt, clock.instant());
			job.terminal(EmailOutboxStatus.SENT, null);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void rejected(Claim claim, boolean retryable) {
		EmailOutbox job = jobs.findForUpdate(claim.id()).orElseThrow();
		if (!job.ownedBy(claim.token()) || job.getStatus() != EmailOutboxStatus.SENDING) {
			return;
		}
		if (retryable && job.getAttemptCount() < properties.maxAttempts()
			&& job.expiresAt().isAfter(clock.instant().plus(properties.retryDelay()))) {
			job.retry(clock.instant().plus(properties.retryDelay()));
			job.delivery().queuedReason("THROTTLED_RETRY_PENDING");
		} else {
			fail(job, EmailOutboxStatus.FAILED, retryable ? "RETRY_EXHAUSTED" : "PROVIDER_REJECTED");
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void unknown(Claim claim) {
		EmailOutbox job = jobs.findForUpdate(claim.id()).orElseThrow();
		if (job.ownedBy(claim.token()) && job.getStatus() == EmailOutboxStatus.SENDING) {
			fail(job, EmailOutboxStatus.UNKNOWN, "DELIVERY_RESULT_UNKNOWN");
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void rateLimited(Claim claim) {
		EmailOutbox job = jobs.findForUpdate(claim.id()).orElseThrow();
		if (job.ownedBy(claim.token()) && job.getStatus() == EmailOutboxStatus.CLAIMED) {
			job.terminal(EmailOutboxStatus.FAILED, "RATE_LIMITED");
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void executorRejected(Long id) {
		EmailOutbox job = jobs.findForUpdate(id).orElse(null);
		if (job != null && (job.getStatus() == EmailOutboxStatus.READY || job.getStatus() == EmailOutboxStatus.RETRY)) {
			job.delivery().queuedReason("EXECUTOR_REJECTED_RETRY_PENDING");
		}
	}

	private void recover(EmailOutbox job, Instant now) {
		if (job.getStatus() == EmailOutboxStatus.SENDING && !job.leaseUntil().isAfter(now)) {
			fail(job, EmailOutboxStatus.UNKNOWN, "DELIVERY_RESULT_UNKNOWN");
		} else if (job.getStatus() == EmailOutboxStatus.CLAIMED && !job.leaseUntil().isAfter(now)) {
			job.recoverClaim();
		}
		if ((job.getStatus() == EmailOutboxStatus.READY || job.getStatus() == EmailOutboxStatus.RETRY
			|| job.getStatus() == EmailOutboxStatus.CLAIMED) && !job.expiresAt().isAfter(now)) {
			fail(job, EmailOutboxStatus.FAILED, "PAYLOAD_EXPIRED");
		}
	}

	private void fail(EmailOutbox job, EmailOutboxStatus status, String code) {
		job.terminal(status, code);
		if (job.delivery().getStatus() == EmailDeliveryStatus.QUEUED) {
			job.delivery().failed(code);
		}
	}

	public record Claim(Long id, String token, EmailMessage message) {
		@Override public String toString() { return "EmailClaim[deliveryId=" + id + "]"; }
	}
}
