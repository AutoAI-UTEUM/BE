package io.edupilot.admin.xai;

import java.math.BigDecimal;
import java.time.YearMonth;

public interface XaiManagementClient {

	PrepaidBalance fetchPrepaidBalance();

	SpendingLimits fetchSpendingLimits();

	InvoicePreview fetchInvoicePreview();

	record PrepaidBalance(BigDecimal balanceUsd) {
	}

	record SpendingLimits(BigDecimal effectiveLimitUsd) {
	}

	record InvoicePreview(
		BigDecimal currentMonthCostUsd,
		YearMonth billingCycle
	) {
	}
}
