package io.edupilot.mail;

public enum EmailOutboxStatus {
	READY, CLAIMED, SENDING, RETRY, SENT, FAILED, UNKNOWN
}
