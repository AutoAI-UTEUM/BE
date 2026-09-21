package io.edupilot.admin.xai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.YearMonth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.edupilot.admin.xai.XaiManagementClient.InvoicePreview;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

class HttpXaiManagementClientTest {

	private static final String API_KEY = "management-secret";
	private static final String TEAM_ID = "team-private-id";

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void callsOfficialBillingEndpointsAndParsesUsdCentsAsBigDecimal()
		throws Exception {
		server.enqueue(json("""
			{
			  "changes": [],
			  "total": {"val": "-12345"}
			}
			"""));
		server.enqueue(json("""
			{
			  "spendingLimits": {
			    "effectiveSl": {"val": "25000"}
			  }
			}
			"""));
		server.enqueue(json("""
			{
			  "coreInvoice": {
			    "totalWithCorr": {"val": "4567"},
			    "prepaidCreditsUsed": {"val": "1234"}
			  },
			  "effectiveSpendingLimit": "25000",
			  "billingCycle": {"year": 2026, "month": 9}
			}
			"""));
		HttpXaiManagementClient client = client(Duration.ofSeconds(1));

		assertThat(client.fetchPrepaidBalance().balanceUsd())
			.isEqualByComparingTo("123.45");
		assertThat(client.fetchSpendingLimits().effectiveLimitUsd())
			.isEqualByComparingTo("250.00");
		InvoicePreview invoice = client.fetchInvoicePreview();
		assertThat(invoice.postpaidUsedUsd())
			.isEqualByComparingTo("45.67");
		assertThat(invoice.prepaidUsedThisPeriodUsd())
			.isEqualByComparingTo("12.34");
		assertThat(invoice.currentMonthCostUsd())
			.isEqualByComparingTo("58.01");
		assertThat(invoice.billingCycle())
			.isEqualTo(YearMonth.of(2026, 9));

		assertRequest(
			server.takeRequest(),
			"/v1/billing/teams/" + TEAM_ID + "/prepaid/balance"
		);
		assertRequest(
			server.takeRequest(),
			"/v1/billing/teams/" + TEAM_ID + "/postpaid/spending-limits"
		);
		assertRequest(
			server.takeRequest(),
			"/v1/billing/teams/" + TEAM_ID + "/postpaid/invoice/preview"
		);
	}

	@Test
	void acceptsMissingPrepaidDeductionAsUnknownWithoutFailingPreview() {
		server.enqueue(json("""
			{
			  "coreInvoice": {
			    "totalWithCorr": {"val": "3714"}
			  },
			  "billingCycle": {"year": 2026, "month": 9}
			}
			"""));

		InvoicePreview invoice = client(Duration.ofSeconds(1))
			.fetchInvoicePreview();

		assertThat(invoice.postpaidUsedUsd())
			.isEqualByComparingTo("37.14");
		assertThat(invoice.currentMonthCostUsd()).isNull();
		assertThat(invoice.prepaidUsedThisPeriodUsd()).isNull();
	}

	@Test
	void listsOnlyRequestedInvoicePeriodAndDropsSensitiveBillingFields()
		throws Exception {
		server.enqueue(json("""
			{
			  "invoices": [{
			    "teamId": "must-not-leak",
			    "paymentMethodId": "must-not-leak",
			    "billingAddress": {"line1": "must-not-leak"},
			    "total": "1234",
			    "invoiceStatus": "PAID",
			    "monthly": {
			      "billingCycle": {"year": 2026, "month": 8}
			    }
			  }]
			}
			"""));
		HttpXaiManagementClient client = client(Duration.ofSeconds(1));

		var invoices = client.fetchInvoices(YearMonth.of(2026, 8));

		assertThat(invoices).singleElement().satisfies(invoice -> {
			assertThat(invoice.billingPeriod()).isEqualTo(YearMonth.of(2026, 8));
			assertThat(invoice.amountUsd()).isEqualByComparingTo("12.34");
			assertThat(invoice.status()).isEqualTo("PAID");
		});
		RecordedRequest request = server.takeRequest();
		assertRequest(
			request,
			"/v1/billing/teams/" + TEAM_ID
				+ "/invoices?billingCycle.year=2026&billingCycle.month=8"
		);
	}

	@Test
	void classifiesConfigurationAndTemporaryFailuresWithoutSecrets() {
		server.enqueue(new MockResponse().setResponseCode(401));
		assertFailure(
			() -> client(Duration.ofSeconds(1)).fetchPrepaidBalance(),
			XaiManagementFailureType.CONFIGURATION_ERROR
		);

		server.enqueue(new MockResponse().setResponseCode(403));
		assertFailure(
			() -> client(Duration.ofSeconds(1)).fetchPrepaidBalance(),
			XaiManagementFailureType.CONFIGURATION_ERROR
		);

		server.enqueue(new MockResponse().setResponseCode(429));
		assertFailure(
			() -> client(Duration.ofSeconds(1)).fetchSpendingLimits(),
			XaiManagementFailureType.TEMPORARY_FAILURE
		);

		server.enqueue(new MockResponse().setResponseCode(503));
		assertFailure(
			() -> client(Duration.ofSeconds(1)).fetchInvoicePreview(),
			XaiManagementFailureType.TEMPORARY_FAILURE
		);
	}

	@Test
	void classifiesReadTimeoutAsTemporaryFailure() {
		server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

		assertFailure(
			() -> client(Duration.ofMillis(50)).fetchPrepaidBalance(),
			XaiManagementFailureType.TEMPORARY_FAILURE
		);
	}

	@Test
	void rejectsMalformedMoneyWithoutEchoingConfiguration() {
		server.enqueue(json("{\"total\":{\"val\":\"not-money\"}}"));

		assertFailure(
			() -> client(Duration.ofSeconds(1)).fetchPrepaidBalance(),
			XaiManagementFailureType.TEMPORARY_FAILURE
		);
	}

	@Test
	void configurationStringRedactsManagementKeyAndTeamId() {
		String rendered = properties(Duration.ofSeconds(1)).toString();

		assertThat(rendered)
			.doesNotContain(API_KEY, TEAM_ID)
			.contains("managementApiKey=[REDACTED]", "teamId=[REDACTED]");
	}

	private HttpXaiManagementClient client(Duration readTimeout) {
		return new HttpXaiManagementClient(properties(readTimeout));
	}

	private XaiManagementProperties properties(Duration readTimeout) {
		return new XaiManagementProperties(
			server.url("/").toString(),
			API_KEY,
			TEAM_ID,
			Duration.ofSeconds(1),
			readTimeout,
			Duration.ofMinutes(2),
			Duration.ofMinutes(2),
			Duration.ofMinutes(5),
			Duration.ofHours(1),
			Duration.ofMinutes(1)
		);
	}

	private MockResponse json(String body) {
		return new MockResponse()
			.setHeader("Content-Type", "application/json")
			.setBody(body);
	}

	private void assertRequest(RecordedRequest request, String path) {
		assertThat(request.getMethod()).isEqualTo("GET");
		assertThat(request.getPath()).isEqualTo(path);
		assertThat(request.getHeader("Authorization"))
			.isEqualTo("Bearer " + API_KEY);
	}

	private void assertFailure(
		Runnable action,
		XaiManagementFailureType expectedType
	) {
		assertThatThrownBy(action::run)
			.isInstanceOfSatisfying(
				XaiManagementClientException.class,
				exception -> {
					assertThat(exception.failureType()).isEqualTo(expectedType);
					assertThat(exception.getMessage())
						.doesNotContain(API_KEY, TEAM_ID);
				}
			);
	}
}
