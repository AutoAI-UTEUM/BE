package io.edupilot.admin.xai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "edupilot.admin.xai")
public record XaiManagementProperties(
	@NotBlank String baseUrl,
	String managementApiKey,
	String teamId,
	@NotNull Duration connectTimeout,
	@NotNull Duration readTimeout,
	@NotNull Duration balanceCacheTtl,
	@NotNull Duration limitsCacheTtl,
	@NotNull Duration invoiceCacheTtl,
	@NotNull Duration historicalInvoiceCacheTtl,
	@NotNull Duration syncRateLimit
) {

	public XaiManagementProperties {
		requirePositive(connectTimeout, "connectTimeout");
		requirePositive(readTimeout, "readTimeout");
		requirePositive(balanceCacheTtl, "balanceCacheTtl");
		requirePositive(limitsCacheTtl, "limitsCacheTtl");
		requirePositive(invoiceCacheTtl, "invoiceCacheTtl");
		requirePositive(
			historicalInvoiceCacheTtl,
			"historicalInvoiceCacheTtl"
		);
		requirePositive(syncRateLimit, "syncRateLimit");
	}

	public boolean configured() {
		return StringUtils.hasText(managementApiKey)
			&& StringUtils.hasText(teamId);
	}

	@Override
	public String toString() {
		return "XaiManagementProperties[baseUrl=" + baseUrl
			+ ", managementApiKey=[REDACTED], teamId=[REDACTED]"
			+ ", connectTimeout=" + connectTimeout
			+ ", readTimeout=" + readTimeout
			+ ", balanceCacheTtl=" + balanceCacheTtl
			+ ", limitsCacheTtl=" + limitsCacheTtl
			+ ", invoiceCacheTtl=" + invoiceCacheTtl
			+ ", historicalInvoiceCacheTtl=" + historicalInvoiceCacheTtl
			+ ", syncRateLimit=" + syncRateLimit + "]";
	}

	private static void requirePositive(Duration value, String name) {
		if (value != null && (value.isZero() || value.isNegative())) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
