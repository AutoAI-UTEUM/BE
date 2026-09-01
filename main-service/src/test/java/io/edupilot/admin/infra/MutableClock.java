package io.edupilot.admin.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

final class MutableClock extends Clock {

	private Instant current;
	private final ZoneId zone;

	MutableClock(Instant current) {
		this(current, ZoneOffset.UTC);
	}

	private MutableClock(Instant current, ZoneId zone) {
		this.current = current;
		this.zone = zone;
	}

	void advance(Duration duration) {
		current = current.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return new MutableClock(current, zone);
	}

	@Override
	public Instant instant() {
		return current;
	}
}
