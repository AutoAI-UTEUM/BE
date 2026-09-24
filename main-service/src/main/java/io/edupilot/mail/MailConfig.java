package io.edupilot.mail;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration(proxyBeanMethods = false)
public class MailConfig {

	@Bean(name = "mailExecutor")
	ThreadPoolTaskExecutor mailExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("mail-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.initialize();
		return executor;
	}

	@Bean
	@ConditionalOnProperty(prefix = "edupilot.mail", name = "provider", havingValue = "ses")
	SesV2Client sesV2Client(MailProperties properties) {
		return SesV2Client.builder()
			.region(Region.of(properties.region()))
			.credentialsProvider(DefaultCredentialsProvider.create())
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.overrideConfiguration(ClientOverrideConfiguration.builder()
				.apiCallTimeout(Duration.ofSeconds(10))
				.build())
			.build();
	}
}
