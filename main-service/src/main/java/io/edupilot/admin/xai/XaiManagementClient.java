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
		BigDecimal prepaidUsedThisPeriodUsd,
		YearMonth billingCycle
	) {

		public BigDecimal postpaidUsedUsd() {
			if (currentMonthCostUsd == null || prepaidUsedThisPeriodUsd == null) {
				return null;
			}
			return currentMonthCostUsd
				.subtract(prepaidUsedThisPeriodUsd)
				.max(BigDecimal.ZERO);
		}
	}

	record InvoiceSummary(
		YearMonth billingPeriod,
		BigDecimal amountUsd,
		BigDecimal periodCostUsd,
		String status
	) {
	}
}
