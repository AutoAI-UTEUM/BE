package io.edupilot.mail;

/** Only a definite provider rejection can be retried; transport timeouts have unknown outcomes. */
public class EmailSendRejection extends RuntimeException {
	private final boolean retryable;
	public EmailSendRejection(boolean retryable) {
		super("Mail provider rejected the request");
		this.retryable = retryable;
	}
	public boolean retryable() { return retryable; }
}
