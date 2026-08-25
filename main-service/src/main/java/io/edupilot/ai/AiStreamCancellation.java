package io.edupilot.ai;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class AiStreamCancellation {

	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final AtomicBoolean userCancelled = new AtomicBoolean();
	private final AtomicReference<Closeable> activeBody =
		new AtomicReference<>();

	public void cancel() {
		cancelled.set(true);
		close(activeBody.getAndSet(null));
	}

	public void cancelByUser() {
		userCancelled.set(true);
		cancel();
	}

	public boolean isCancelled() {
		return cancelled.get();
	}

	public boolean isUserCancelled() {
		return userCancelled.get();
	}

	void bind(Closeable body) {
		if (!activeBody.compareAndSet(null, body)) {
			throw new IllegalStateException("AI stream body is already bound");
		}
		if (cancelled.get() && activeBody.compareAndSet(body, null)) {
			close(body);
		}
	}

	void unbind(Closeable body) {
		activeBody.compareAndSet(body, null);
	}

	private void close(Closeable body) {
		if (body == null) {
			return;
		}
		try {
			body.close();
		} catch (IOException ignored) {
			// Closing is best-effort and only used to unblock a stream read.
		}
	}
}
