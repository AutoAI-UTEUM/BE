package io.edupilot.guardian.team;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GuardianTeamProperties.class)
public class GuardianTeamConfig {
	@Bean
	GuardianTeamActivation guardianTeamActivation(GuardianTeamProperties properties) {
		properties.validateActivation();
		return new GuardianTeamActivation();
	}
	static final class GuardianTeamActivation { }
}
