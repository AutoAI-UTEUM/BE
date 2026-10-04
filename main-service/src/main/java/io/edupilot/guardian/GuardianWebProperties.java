package io.edupilot.guardian;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("edupilot.guardian.web")
public record GuardianWebProperties(
	@DefaultValue("false") boolean enabled,
	@DefaultValue("30m") Duration linkTtl,
	@DefaultValue("5m") Duration phoneTtl,
	@DefaultValue("5") int maxCodeAttempts,
	@DefaultValue("") String portalBaseUrl,
	@DefaultValue("") String noticeVersion,
	@DefaultValue("") String noticeDigest,
	@DefaultValue("") String noticeUrl
) { }
