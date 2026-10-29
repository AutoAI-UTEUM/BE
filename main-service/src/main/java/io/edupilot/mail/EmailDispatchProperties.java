package io.edupilot.mail;

import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** A trial approves exact durable jobs, rather than draining the provider's whole queue. */
@ConfigurationProperties(prefix = "edupilot.mail.outbox.dispatch")
public record EmailDispatchProperties(
	@DefaultValue("NORMAL") Mode mode,
	Set<Long> deliveryIds,
	String recipient
) {
	private static final Pattern RECIPIENT = Pattern.compile(
		"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$"
	);

	public EmailDispatchProperties {
		if (mode == null) {
			throw new IllegalArgumentException("Mail dispatch mode is required");
		}
		deliveryIds = deliveryIds == null ? Set.of() : Set.copyOf(deliveryIds);
		recipient = recipient == null ? "" : recipient;
		if (mode == Mode.ISOLATED_TRIAL) {
			if (deliveryIds.isEmpty() || deliveryIds.size() > 3 || deliveryIds.stream().anyMatch(id -> id <= 0)
				|| recipient.length() > 320 || !RECIPIENT.matcher(recipient).matches()) {
				throw new IllegalArgumentException("Mail trial requires one recipient and one to three positive delivery IDs");
			}
		} else if (!deliveryIds.isEmpty() || !recipient.isEmpty()) {
			throw new IllegalArgumentException("Mail dispatch approvals require ISOLATED_TRIAL mode");
		}
	}

	public enum Mode { NORMAL, PAUSED, ISOLATED_TRIAL }

	boolean blocks(Long id) {
		return mode == Mode.PAUSED || (mode == Mode.ISOLATED_TRIAL && !deliveryIds.contains(id));
	}

	boolean permits(EmailOutbox job) {
		return !blocks(job.getId()) && (mode != Mode.ISOLATED_TRIAL
			|| (recipient.equals(job.delivery().getRecipient())
				&& job.getAttemptCount() == 0 && job.delivery().getAttemptCount() == 0));
	}

	boolean permits(EmailOutboxStore.Claim claim) {
		return !blocks(claim.id()) && (mode != Mode.ISOLATED_TRIAL
			|| recipient.equals(claim.message().to()));
	}

	@Override public String toString() {
		return "EmailDispatchProperties[mode=" + mode + ", approvedDeliveries=" + deliveryIds.size() + "]";
	}
}
