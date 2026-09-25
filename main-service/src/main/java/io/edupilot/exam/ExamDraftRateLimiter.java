package io.edupilot.exam;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

@Component
public class ExamDraftRateLimiter {

	private final Cache<Key, AtomicInteger> requests;

	public ExamDraftRateLimiter() {
		this(Ticker.systemTicker());
	}

	ExamDraftRateLimiter(Ticker ticker) {
		requests = Caffeine.newBuilder()
			.maximumSize(100_000)
			.expireAfterWrite(Duration.ofMinutes(1))
			.ticker(ticker)
			.build();
	}

	public boolean allow(Long userId, Long examId) {
		return requests.get(new Key(userId, examId), ignored -> new AtomicInteger())
			.incrementAndGet() <= 30;
	}

	private record Key(Long userId, Long examId) {
	}
}
