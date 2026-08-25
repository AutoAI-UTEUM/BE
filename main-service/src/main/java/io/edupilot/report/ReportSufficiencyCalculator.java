package io.edupilot.report;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ReportSufficiencyCalculator {

	public static final String ELIGIBLE = "ELIGIBLE";
	public static final String NO_ALLOWED_SOURCE_DATA = "NO_ALLOWED_SOURCE_DATA";
	public static final String INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

	public ReportSnapshot.DataQuality calculate(
		String policyVersion,
		List<ReportCriterionDefinition> catalog,
		List<ReportSnapshot.Evidence> evidence
	) {
		EnumSet<ReportSourceType> availableSources = EnumSet.noneOf(ReportSourceType.class);
		evidence.stream()
			.map(ReportSnapshot.Evidence::sourceType)
			.forEach(availableSources::add);
		EnumSet<ReportSourceType> missingSources = EnumSet.allOf(ReportSourceType.class);
		missingSources.removeAll(availableSources);

		Map<String, ReportSnapshot.Eligibility> eligibility = new LinkedHashMap<>();
		for (ReportCriterionDefinition criterion : catalog) {
			int evidenceCount = Math.toIntExact(evidence.stream()
				.filter(item -> criterion.allowedSources().contains(item.sourceType()))
				.count());
			boolean eligible = evidenceCount >= criterion.minEvidence();
			String reason;
			if (eligible) {
				reason = ELIGIBLE;
			} else if (criterion.allowedSources().stream()
				.noneMatch(availableSources::contains)) {
				reason = NO_ALLOWED_SOURCE_DATA;
			} else {
				reason = INSUFFICIENT_EVIDENCE;
			}
			eligibility.put(criterion.key(), new ReportSnapshot.Eligibility(
				eligible,
				reason,
				evidenceCount,
				criterion.minEvidence()
			));
		}
		return new ReportSnapshot.DataQuality(
			policyVersion,
			availableSources,
			missingSources,
			eligibility
		);
	}
}
