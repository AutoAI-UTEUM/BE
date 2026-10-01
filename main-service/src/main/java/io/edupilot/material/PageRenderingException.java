package io.edupilot.material;

import io.edupilot.material.storage.StorageException;

class PageRenderingException extends StorageException {

	private final CaptionFailureReason reason;

	PageRenderingException(CaptionFailureReason reason) {
		super(reason.name());
		this.reason = reason;
	}

	CaptionFailureReason reason() {
		return reason;
	}
}
