package io.edupilot.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookie {

	public static final String NAME = "edupilot_refresh";
	private static final String PATH = "/api/auth";

	private final JwtProperties jwtProperties;

	public RefreshTokenCookie(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
	}

	public ResponseCookie create(String rawToken) {
		return base(rawToken)
			.maxAge(jwtProperties.refreshTokenTtl())
			.build();
	}

	public ResponseCookie expire() {
		return base("")
			.maxAge(Duration.ZERO)
			.build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Lax")
			.path(PATH);
	}
}
