package io.edupilot.usernote;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

@Component
public class NoteImportRateLimiter {

	private final Cache<Long, AtomicInteger> requests;

	public NoteImportRateLimiter() {
		this(Ticker.systemTicker());
	}

	NoteImportRateLimiter(Ticker ticker) {
		requests = Caffeine.newBuilder()
			.maximumSize(100_000)
			.expireAfterWrite(Duration.ofMinutes(1))
			.ticker(ticker)
			.build();
	}

	public boolean allow(Long userId) {
		return requests.get(userId, ignored -> new AtomicInteger())
			.incrementAndGet() <= 5;
	}
}
