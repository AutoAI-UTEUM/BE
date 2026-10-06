package io.edupilot.guardian.team;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

/** Privacy-safe event facts. No guardian contact, evidence reference, reply, or free-text reason. */
@Entity
@Table(name = "guardian_team_events")
public class GuardianTeamEvent {
	@Id @Column(length = 36) private String id;
	@Column(name = "request_id", length = 36, nullable = false) private String requestId;
	@Column(nullable = false) private long generation;
	@Column(nullable = false) private long revision;
	@Column(name = "event_type", length = 40, nullable = false) private String eventType;
	@Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) private GuardianTeamRequest.State state;
	@Column(name = "actor_id") private Long actorId;
	@Column(name = "recorded_at", nullable = false) private Instant recordedAt;
	@Column(name = "erase_due_at", nullable = false) private Instant eraseDueAt;
	protected GuardianTeamEvent() { }
	static GuardianTeamEvent record(GuardianTeamRequest row, String type, Long actorId, Instant now, Instant eraseDueAt) {
		var event = new GuardianTeamEvent(); event.id = UUID.randomUUID().toString(); event.requestId = row.id();
		event.generation = row.generation(); event.revision = row.revision(); event.eventType = type; event.state = row.state();
		event.actorId = actorId; event.recordedAt = now; event.eraseDueAt = eraseDueAt; return event;
	}
	public String eventType() { return eventType; }
	public Instant recordedAt() { return recordedAt; }
	public Long actorId() { return actorId; }
	public long generation() { return generation; }
	public long revision() { return revision; }
	public GuardianTeamRequest.State state() { return state; }
}
