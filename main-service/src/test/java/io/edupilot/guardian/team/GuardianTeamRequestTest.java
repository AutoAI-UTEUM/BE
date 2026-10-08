package io.edupilot.guardian.team;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.edupilot.user.User;
import io.edupilot.user.UserRole;

class GuardianTeamRequestTest {
	private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
	private static final GuardianTeamProperties POLICY = GuardianTeamPropertiesTest.settings(Duration.ofDays(30), Duration.ofDays(7), List.of("SERVICE"), true);
	private GuardianTeamRequest row(String name, String contact) {
		return GuardianTeamRequest.create(User.create("child@example.invalid", "hash", "합성 아동", UserRole.LEARNER), POLICY,
			POLICY.configurationDigest(), NOW, name, contact);
	}
	@Test void linkReissueDoesNotExtendFirstCollectionOrRequestDeadline() {
		var row = row("합성 보호자", "guardian@example.invalid");
		row.issueToken("a".repeat(64), NOW.plusSeconds(60), Duration.ofMinutes(30));
		row.issueToken("b".repeat(64), NOW.plusSeconds(3600), Duration.ofMinutes(30));
		assertThat(row.firstCollectedAt()).isEqualTo(NOW);
		assertThat(row.unconfirmedEraseDueAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));
		assertThat(row.requestExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));
		assertThat(row.tokenUsable("a".repeat(64), NOW.plusSeconds(3600))).isFalse();
		assertThat(row.tokenUsable("b".repeat(64), NOW.plusSeconds(3600))).isTrue();
	}
	@Test void aDeclarationConsumesItsTokenAndKeepsApprovalSeparate() {
		var row = row(null, null); row.issueToken("a".repeat(64), NOW, Duration.ofMinutes(30));
		row.declare("합성 보호자", "guardian@example.invalid", GuardianTeamRequest.Relationship.PARENT, "SERVICE", NOW.plusSeconds(20));
		assertThat(row.state()).isEqualTo(GuardianTeamRequest.State.DECLARED);
		assertThat(row.tokenUsable("a".repeat(64), NOW.plusSeconds(21))).isFalse();
		assertThat(row.explicitResponseAt()).isNull(); assertThat(row.approvedUntil()).isNull();
		assertThat(row.firstCollectedAt()).isEqualTo(NOW.plusSeconds(20));
		assertThat(row.contactOrigin()).isEqualTo(GuardianTeamRequest.ContactOrigin.GUARDIAN);
	}
	@Test void reissueAfterAResponseInvalidatesTheOldConfirmation() {
		var row = row(null, null);
		row.confirm(GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY, "opaque-ref", NOW, 1L,
			GuardianTeamRequest.Relationship.PARENT, "SERVICE", NOW);
		row.issueToken("b".repeat(64), NOW.plusSeconds(10), Duration.ofMinutes(30));
		assertThat(row.explicitResponseAt()).isNull(); assertThat(row.evidenceReference()).isNull();
		assertThat(row.legalMethodChecked()).isFalse();
	}
	@Test void rejectionErasesContactsAndEvidenceWithoutKeepingAReplyBody() {
		var row = row("합성 보호자", "guardian@example.invalid");
		row.confirm(GuardianTeamRequest.ConfirmationMethod.PHONE, "opaque-ref", NOW, 1L,
			GuardianTeamRequest.Relationship.PARENT, "SERVICE", NOW);
		row.end(GuardianTeamRequest.State.REJECTED, GuardianTeamRequest.Reason.RELATIONSHIP_UNCONFIRMED, NOW.plusSeconds(10));
		assertThat(row.guardianName()).isNull(); assertThat(row.guardianContact()).isNull();
		assertThat(row.evidenceReference()).isNull(); assertThat(row.declaredScopes()).isNull();
		assertThat(row.state()).isEqualTo(GuardianTeamRequest.State.REJECTED);
	}
	@Test void fiveDayDeadlineAndApprovalEvidencePeriodRemainDifferent() {
		var row = row("합성 보호자", "guardian@example.invalid");
		row.confirm(GuardianTeamRequest.ConfirmationMethod.EMAIL_REPLY, "opaque-ref", NOW, 1L,
			GuardianTeamRequest.Relationship.PARENT, "SERVICE", NOW);
		row.approve(1L, NOW.plusSeconds(10), Duration.ofDays(7), Duration.ofDays(30));
		assertThat(row.unconfirmedEraseDueAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));
		assertThat(row.evidenceEraseDueAt()).isEqualTo(NOW.plusSeconds(10).plus(Duration.ofDays(30)));
		assertThat(row.approvedUntil()).isEqualTo(NOW.plusSeconds(10).plus(Duration.ofDays(7)));
		assertThat(row.guardianContact()).isNull(); assertThat(row.guardianName()).isNull();
	}
}
