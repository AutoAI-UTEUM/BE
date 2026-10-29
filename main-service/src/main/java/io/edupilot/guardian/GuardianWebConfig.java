package io.edupilot.guardian;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardianWebProperties.class)
public class GuardianWebConfig {
	@Bean
	@ConditionalOnMissingBean(GuardianPhoneProvider.class)
	GuardianPhoneProvider disconnectedGuardianPhoneProvider() {
		return new GuardianPhoneProvider() {
			public boolean connected() { return false; }
			public Receipt send(String phone, String key, java.time.Instant expires) { throw unavailable(); }
			public Result verify(String reference, String code, String key, java.time.Instant expires) { throw unavailable(); }
			private Failure unavailable() { return new Failure(Category.UNAVAILABLE); }
		};
	}
}
