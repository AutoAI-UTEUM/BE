package io.edupilot.guardian.team;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.edupilot.guardian.team.mail.GuardianTeamMailCleanup;

/** Same withdrawal cleanup, without intake policies, token generation or providers. */
public class GuardianTeamWithdrawalCleanup {
	private final GuardianTeamRequestRepository requests;
	private final GuardianTeamEventRepository events;
	private final GuardianTeamOperationRepository operations;
	private final GuardianTeamMailCleanup mail;
	private final Clock clock;
	public GuardianTeamWithdrawalCleanup(GuardianTeamRequestRepository requests, GuardianTeamEventRepository events,
		GuardianTeamOperationRepository operations, GuardianTeamMailCleanup mail, Clock clock) {
		this.requests=requests; this.events=events; this.operations=operations; this.mail=mail; this.clock=clock;
	}
	@Transactional(propagation = Propagation.MANDATORY)
	public void cancelOnWithdrawal(Long userId) { cancel(requests, events, operations, mail, clock, userId); }
	static void cancel(GuardianTeamRequestRepository requests, GuardianTeamEventRepository events,
		GuardianTeamOperationRepository operations, GuardianTeamMailCleanup mail, Clock clock, Long userId) {
		var row=requests.findByUserForUpdate(userId).orElse(null);
		if (row==null) { return; }
		row.end(GuardianTeamRequest.State.WITHDRAWN, GuardianTeamRequest.Reason.ACCOUNT_WITHDRAWN, now(clock));
		row.user().clearGuardianTeamApproval(false); eraseCopies(row, operations, mail);
		events.setGenerationDeadline(row.id(), row.generation(), now(clock)); events.eraseDue(row.id(), now(clock));
		operations.eraseForRequest(row.id());
	}
	static void eraseCopies(GuardianTeamRequest row, GuardianTeamOperationRepository operations, GuardianTeamMailCleanup mail) {
		mail.purgeForRequest(row.id()); operations.eraseForRequest(row.id());
	}
	private static Instant now(Clock clock) { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
