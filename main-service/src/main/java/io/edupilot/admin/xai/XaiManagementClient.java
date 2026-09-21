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
		BigDecimal postpaidUsedUsd,
		BigDecimal prepaidUsedThisPeriodUsd,
		YearMonth billingCycle
	) {

		public BigDecimal currentMonthCostUsd() {
			if (postpaidUsedUsd == null || prepaidUsedThisPeriodUsd == null) {
				return null;
			}
			return postpaidUsedUsd.add(prepaidUsedThisPeriodUsd);
		}
	}

	record InvoiceSummary(
		YearMonth billingPeriod,
		BigDecimal amountUsd,
		String status
	) {
	}
}
