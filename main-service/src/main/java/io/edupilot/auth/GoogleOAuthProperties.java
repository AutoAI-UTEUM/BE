package io.edupilot.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edupilot.google")
public record GoogleOAuthProperties(String clientId) {
}
