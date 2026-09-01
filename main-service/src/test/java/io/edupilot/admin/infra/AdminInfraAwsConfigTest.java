package io.edupilot.admin.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;

class AdminInfraAwsConfigTest {

	@Test
	void configuresRegionalCloudWatchAndGlobalCostExplorerWithTimeouts() {
		AdminInfraAwsConfig config = new AdminInfraAwsConfig();
		AdminInfraProperties properties = new AdminInfraProperties(
			true,
			"ap-northeast-2",
			new AdminInfraProperties.Instances("i-prod", "i-dev"),
			Duration.ofMinutes(5),
			Duration.ofHours(12)
		);

		try (
			CloudWatchClient cloudWatch = config.cloudWatchClient(properties);
			CostExplorerClient costExplorer = config.costExplorerClient()
		) {
			assertThat(cloudWatch.serviceClientConfiguration().region())
				.isEqualTo(Region.AP_NORTHEAST_2);
			assertThat(costExplorer.serviceClientConfiguration().region())
				.isEqualTo(Region.US_EAST_1);
			assertThat(cloudWatch.serviceClientConfiguration()
				.overrideConfiguration()
				.apiCallTimeout()).contains(Duration.ofSeconds(5));
			assertThat(costExplorer.serviceClientConfiguration()
				.overrideConfiguration()
				.apiCallTimeout()).contains(Duration.ofSeconds(5));
		}
	}
}
