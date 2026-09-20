package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Component
public class HttpXaiManagementClient implements XaiManagementClient {

	private static final String PREPAID_BALANCE_PATH =
		"/v1/billing/teams/{teamId}/prepaid/balance";
	private static final String SPENDING_LIMITS_PATH =
		"/v1/billing/teams/{teamId}/postpaid/spending-limits";
	private static final String INVOICE_PREVIEW_PATH =
		"/v1/billing/teams/{teamId}/postpaid/invoice/preview";
	private static final String INVOICES_PATH =
		"/v1/billing/teams/{teamId}/invoices";

	private final XaiManagementProperties properties;
	private final RestClient restClient;

	public HttpXaiManagementClient(XaiManagementProperties properties) {
		this.properties = properties;
		this.restClient = properties.configured()
			? buildRestClient(properties)
			: null;
	}

	@Override
	public PrepaidBalance fetchPrepaidBalance() {
		PrepaidBalanceResponse response = get(
			PREPAID_BALANCE_PATH,
			PrepaidBalanceResponse.class
		);
		// xAI represents purchased prepaid credit as a negative ledger balance.
		return new PrepaidBalance(usdCents(response.total()).negate());
	}

	@Override
	public SpendingLimits fetchSpendingLimits() {
		SpendingLimitsResponse response = get(
			SPENDING_LIMITS_PATH,
			SpendingLimitsResponse.class
		);
		Money effective = response.spendingLimits() == null
			? null
			: response.spendingLimits().effectiveSl();
		return new SpendingLimits(usdCents(effective));
	}

	@Override
	public InvoicePreview fetchInvoicePreview() {
		InvoicePreviewResponse response = get(
			INVOICE_PREVIEW_PATH,
			InvoicePreviewResponse.class
		);
		if (response.coreInvoice() == null
			|| response.billingCycle() == null) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
		try {
			return new InvoicePreview(
				usdCents(response.coreInvoice().totalWithCorr()),
				YearMonth.of(
					response.billingCycle().year(),
					response.billingCycle().month()
				)
			);
		} catch (RuntimeException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
	}

	@Override
	public List<InvoiceSummary> fetchInvoices(YearMonth billingPeriod) {
		if (restClient == null) {
			throw failure(XaiManagementFailureType.CONFIGURATION_ERROR);
		}
		try {
			InvoicesResponse response = restClient.get()
				.uri(builder -> builder
					.path(INVOICES_PATH)
					.queryParam("billingCycle.year", billingPeriod.getYear())
					.queryParam("billingCycle.month", billingPeriod.getMonthValue())
					.build(properties.teamId()))
				.retrieve()
				.body(InvoicesResponse.class);
			if (response == null || response.invoices() == null) {
				throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
			}
			return response.invoices().stream()
				.filter(invoice -> invoice.monthly() != null
					&& invoice.monthly().billingCycle() != null)
				.map(this::invoiceSummary)
				.filter(invoice -> invoice.billingPeriod().equals(billingPeriod))
				.toList();
		} catch (XaiManagementClientException exception) {
			throw exception;
		} catch (RestClientResponseException exception) {
			throw failure(classify(exception.getStatusCode().value()));
		} catch (ResourceAccessException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		} catch (RestClientException | IllegalArgumentException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
	}

	private <T> T get(String path, Class<T> responseType) {
		if (restClient == null) {
			throw failure(XaiManagementFailureType.CONFIGURATION_ERROR);
		}
		try {
			T response = restClient.get()
				.uri(path, properties.teamId())
				.retrieve()
				.body(responseType);
			if (response == null) {
				throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
			}
			return response;
		} catch (XaiManagementClientException exception) {
			throw exception;
		} catch (RestClientResponseException exception) {
			throw failure(classify(exception.getStatusCode().value()));
		} catch (ResourceAccessException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		} catch (RestClientException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		} catch (IllegalArgumentException exception) {
			throw failure(XaiManagementFailureType.CONFIGURATION_ERROR);
		}
	}

	private RestClient buildRestClient(XaiManagementProperties settings) {
		SimpleClientHttpRequestFactory requestFactory =
			new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(settings.connectTimeout());
		requestFactory.setReadTimeout(settings.readTimeout());
		return RestClient.builder()
			.baseUrl(settings.baseUrl())
			.defaultHeader(
				HttpHeaders.AUTHORIZATION,
				"Bearer " + settings.managementApiKey()
			)
			.requestFactory(requestFactory)
			.build();
	}

	private BigDecimal usdCents(Money money) {
		if (money == null || money.val() == null || money.val().isBlank()) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
		try {
			return new BigDecimal(money.val()).movePointLeft(2);
		} catch (NumberFormatException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
	}

	private InvoiceSummary invoiceSummary(InvoiceBody invoice) {
		try {
			if (invoice.invoiceStatus() == null
				|| invoice.invoiceStatus().isBlank()) {
				throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
			}
			BillingCycle cycle = invoice.monthly().billingCycle();
			return new InvoiceSummary(
				YearMonth.of(cycle.year(), cycle.month()),
				usdCents(invoice.total()),
				invoice.invoiceStatus()
			);
		} catch (RuntimeException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
	}

	private BigDecimal usdCents(String cents) {
		// xAI Billing Management invoice totals use decimal strings in USD cents.
		if (cents == null || cents.isBlank()) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
		try {
			return new BigDecimal(cents).movePointLeft(2);
		} catch (NumberFormatException exception) {
			throw failure(XaiManagementFailureType.TEMPORARY_FAILURE);
		}
	}

	private XaiManagementFailureType classify(int status) {
		if (status == 401 || status == 403) {
			return XaiManagementFailureType.CONFIGURATION_ERROR;
		}
		if (status == 429 || status >= 500) {
			return XaiManagementFailureType.TEMPORARY_FAILURE;
		}
		return XaiManagementFailureType.CONFIGURATION_ERROR;
	}

	private XaiManagementClientException failure(
		XaiManagementFailureType failureType
	) {
		return new XaiManagementClientException(failureType);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Money(String val) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record PrepaidBalanceResponse(Money total) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record SpendingLimitsResponse(SpendingLimitsBody spendingLimits) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record SpendingLimitsBody(Money effectiveSl) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record InvoicePreviewResponse(
		CoreInvoice coreInvoice,
		BillingCycle billingCycle
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record CoreInvoice(Money totalWithCorr) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record BillingCycle(int year, int month) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record InvoicesResponse(List<InvoiceBody> invoices) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record InvoiceBody(
		String total,
		String invoiceStatus,
		MonthlyInvoice monthly
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record MonthlyInvoice(BillingCycle billingCycle) {
	}
}
