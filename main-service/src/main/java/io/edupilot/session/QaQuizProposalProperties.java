package io.edupilot.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QaQuizProposalProperties {

	private final boolean enabled;
	private final int rejectionThreshold;
	private final int suppressionTurns;

	public QaQuizProposalProperties(
		@Value("${edupilot.ai.capabilities.qa-quiz-proposal:false}")
		boolean enabled,
		@Value("${edupilot.ai.qa-quiz-proposal.rejection-threshold:2}")
		int rejectionThreshold,
		@Value("${edupilot.ai.qa-quiz-proposal.suppression-turns:5}")
		int suppressionTurns
	) {
		if (rejectionThreshold < 1 || suppressionTurns < 1) {
			throw new IllegalArgumentException(
				"QA quiz proposal suppression settings must be positive"
			);
		}
		this.enabled = enabled;
		this.rejectionThreshold = rejectionThreshold;
		this.suppressionTurns = suppressionTurns;
	}

	public boolean enabled() { return enabled; }

	public int rejectionThreshold() { return rejectionThreshold; }

	public int suppressionTurns() { return suppressionTurns; }
}
