package io.edupilot.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "edupilot.mail")
public record MailProperties(
	boolean enabled,
	String provider,
	String from,
	String replyTo,
	String baseUrl,
	String region
) {
}
