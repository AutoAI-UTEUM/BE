package io.edupilot.guardian;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database-only cancellation shared with the maintenance context; no phone/JWT provider. */
public class GuardianWebCancellation {
	private final GuardianWebRequestRepository requests;
	public GuardianWebCancellation(GuardianWebRequestRepository requests) { this.requests = requests; }
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelUser(Long userId) { cancel(requests, userId); }
	static void cancel(GuardianWebRequestRepository requests, Long userId) {
		requests.findByUserIdForUpdate(userId).forEach(GuardianWebRequest::cancel);
	}
}
