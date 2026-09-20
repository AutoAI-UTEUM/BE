package io.edupilot.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookie {

	public static final String NAME = "edupilot_refresh";
	private static final String PATH = "/api/auth";

	public ResponseCookie create(String rawToken, Duration maxAge) {
		return base(rawToken)
			.maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
			.build();
	}

	public ResponseCookie expire() {
		return expired();
	}

	public static ResponseCookie expired() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Lax")
			.path(PATH);
	}
}
