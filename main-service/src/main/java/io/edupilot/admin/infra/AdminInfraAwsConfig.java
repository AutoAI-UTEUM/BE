package io.edupilot.admin.infra;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "edupilot.admin.infra",
	name = "enabled",
	havingValue = "true"
)
public class AdminInfraAwsConfig {

	private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

	@Bean
	CloudWatchClient cloudWatchClient(AdminInfraProperties properties) {
		return CloudWatchClient.builder()
			.region(Region.of(properties.region()))
			.credentialsProvider(DefaultCredentialsProvider.create())
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.overrideConfiguration(clientOverrideConfiguration())
			.build();
	}

	@Bean
	CostExplorerClient costExplorerClient() {
		return CostExplorerClient.builder()
			// Cost Explorer is a global service exposed through us-east-1.
			.region(Region.US_EAST_1)
			.credentialsProvider(DefaultCredentialsProvider.create())
			.httpClientBuilder(UrlConnectionHttpClient.builder())
			.overrideConfiguration(clientOverrideConfiguration())
			.build();
	}

	private ClientOverrideConfiguration clientOverrideConfiguration() {
		return ClientOverrideConfiguration.builder()
			.apiCallTimeout(API_CALL_TIMEOUT)
			.build();
	}
}
