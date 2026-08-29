package io.edupilot.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class H2TimeZoneFunctions {

	private H2TimeZoneFunctions() {
	}

	public static Timestamp convertTz(
		Timestamp value,
		String fromOffset,
		String toOffset
	) {
		if (value == null) {
			return null;
		}
		Instant instant = value.toLocalDateTime()
			.toInstant(ZoneOffset.of(fromOffset));
		return Timestamp.valueOf(LocalDateTime.ofInstant(
			instant,
			ZoneOffset.of(toOffset)
		));
	}
}
