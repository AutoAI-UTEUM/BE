package io.edupilot.guardian;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;

/** Approved calendar-year policy; it does not establish age or guardian evidence. */
public final class BirthdatePolicy {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private BirthdatePolicy() { }

	public static LocalDate today(Clock clock) {
		return LocalDate.ofInstant(clock.instant(), SEOUL);
	}

	public static void validate(LocalDate date, Clock clock) {
		if (!validOn(date, today(clock))) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	public static boolean guardianNotRequired(LocalDate date, Clock clock) {
		LocalDate today = today(clock);
		return validOn(date, today) && today.getYear() - date.getYear() >= 15;
	}

	private static boolean validOn(LocalDate date, LocalDate today) {
		return date != null && date.getYear() >= 1 && date.getYear() <= 9999 && !date.isAfter(today);
	}
}
