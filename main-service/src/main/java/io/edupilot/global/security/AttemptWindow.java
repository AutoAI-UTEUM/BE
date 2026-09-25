package io.edupilot.global.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

/** Fixed window starts with the first attempt, not the most recent failure. */
public final class AttemptWindow<K> {

	private final Cache<K, Counter> attempts;
	private final Ticker ticker;
	private final long windowNanos;

	public AttemptWindow(Duration window, long maximumSize, Ticker ticker) {
		this.ticker = ticker;
		this.windowNanos = window.toNanos();
		this.attempts = Caffeine.newBuilder()
			.maximumSize(maximumSize)
			.expireAfterWrite(window)
			.ticker(ticker)
			.build();
	}

	public int increment(K key) {
		return attempts.get(key, ignored -> new Counter(ticker.read()))
			.count.incrementAndGet();
	}

	public boolean isBlocked(K key, int limit) {
		Counter counter = attempts.getIfPresent(key);
		return counter != null && counter.count.get() >= limit;
	}

	public long retryAfterSeconds(K key) {
		Counter counter = attempts.getIfPresent(key);
		if (counter == null) {
			return 0;
		}
		long remainingNanos = Math.max(0, windowNanos - (ticker.read() - counter.startedAtNanos));
		return Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L);
	}

	public void reset(K key) {
		attempts.invalidate(key);
	}

	private static final class Counter {
		private final long startedAtNanos;
		private final AtomicInteger count = new AtomicInteger();

		private Counter(long startedAtNanos) {
			this.startedAtNanos = startedAtNanos;
		}
	}
}
