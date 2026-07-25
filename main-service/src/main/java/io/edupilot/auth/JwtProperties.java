package io.edupilot.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.jwt")
public record JwtProperties(
	@NotBlank String secret,
	@NotNull Duration accessTokenTtl,
	@NotNull Duration refreshTokenTtl
) {
}
