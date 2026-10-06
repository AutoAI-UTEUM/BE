package io.edupilot.guardian.team;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/** Idempotency receipts contain a digest only; no raw contact or submitted reply is retained. */
@Entity
@Table(name = "guardian_team_operations", uniqueConstraints = @UniqueConstraint(columnNames = {"request_id", "operation_key"}))
public class GuardianTeamOperation {
	@Id @Column(length = 36) private String id;
	@Column(name = "request_id", length = 36, nullable = false) private String requestId;
	@Column(name = "operation_key", length = 100, nullable = false) private String operationKey;
	@Column(name = "input_digest", length = 64, nullable = false) private String inputDigest;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	@Column(name = "erase_due_at", nullable = false) private Instant eraseDueAt;
	protected GuardianTeamOperation() { }
	static GuardianTeamOperation record(String requestId, String key, String digest, Instant now, Instant due) {
		var receipt = new GuardianTeamOperation(); receipt.id = UUID.randomUUID().toString(); receipt.requestId = requestId;
		receipt.operationKey = key; receipt.inputDigest = digest; receipt.createdAt = now; receipt.eraseDueAt = due; return receipt;
	}
	String inputDigest() { return inputDigest; }
}
