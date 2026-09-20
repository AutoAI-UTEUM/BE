package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

public interface XaiManagementClient {

	PrepaidBalance fetchPrepaidBalance();

	SpendingLimits fetchSpendingLimits();

	InvoicePreview fetchInvoicePreview();

	List<InvoiceSummary> fetchInvoices(YearMonth billingPeriod);

	record PrepaidBalance(BigDecimal balanceUsd) {
	}

	record SpendingLimits(BigDecimal effectiveLimitUsd) {
	}

	record InvoicePreview(
		BigDecimal currentMonthCostUsd,
		YearMonth billingCycle
	) {
	}

	record InvoiceSummary(
		YearMonth billingPeriod,
		BigDecimal amountUsd,
		String status
	) {
	}
}
