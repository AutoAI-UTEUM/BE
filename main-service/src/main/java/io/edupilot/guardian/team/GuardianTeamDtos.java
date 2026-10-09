package io.edupilot.guardian.team;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public final class GuardianTeamDtos {
	private GuardianTeamDtos() { }
	public record Intake(@NotBlank @Size(max = 64) String idempotencyKey,
		@Size(max = 100) String guardianName, @Size(max = 254) String guardianContact,
		Boolean guardianContactProvidedByChild) {
		@Override public String toString() { return "GuardianTeamIntake[REDACTED]"; }
	}
	public record Mutation(@NotBlank @Size(max = 64) String idempotencyKey, @Positive long generation, @Positive long revision) { }
	public record Token(@NotBlank @Size(max = 43) String token) {
		@Override public String toString() { return "GuardianTeamToken[REDACTED]"; }
	}
	public record Consent(@NotBlank @Size(max = 43) String token, @NotBlank @Size(max = 64) String idempotencyKey,
		@Positive long generation, @Positive long revision, @NotBlank String noticeVersion, @NotBlank String noticeDigest,
		boolean accepted, boolean declaresLegalGuardian, GuardianTeamRequest.Relationship relationship,
		Set<String> scopes, @Size(max = 100) String guardianName, @Size(max = 254) String guardianContact) {
		@Override public String toString() { return "GuardianTeamConsent[REDACTED]"; }
	}
	public record Confirmation(@NotBlank @Size(max = 64) String idempotencyKey, @Positive long generation, @Positive long revision,
		@NotNull GuardianTeamRequest.ConfirmationMethod method, @NotBlank @Size(max = 100) String evidenceReference,
		@NotNull Instant responseReceivedAt, @NotBlank String noticeVersion, @NotBlank String noticeDigest,
		@NotNull GuardianTeamRequest.Relationship relationship, Set<String> scopes,
		boolean requestReferenceMatched, boolean responseExplicitlyConsents, boolean legalGuardianDeclarationConfirmed,
		boolean noticeAndScopesMatched, boolean confirmationMethodChecked) {
		@Override public String toString() { return "GuardianTeamConfirmation[REDACTED]"; }
	}
	public record Decision(@NotBlank @Size(max = 64) String idempotencyKey, @Positive long generation, @Positive long revision,
		@NotNull DecisionKind decision, GuardianTeamRequest.Reason reason,
		boolean relationshipChecked, boolean guardianContactChecked, boolean evidenceReferenceChecked,
		boolean noticeAndScopesChecked) { }
	public record Revoke(@NotBlank @Size(max = 64) String idempotencyKey, @Positive long generation, @Positive long revision,
		@NotNull GuardianTeamRequest.Reason reason) { }
	public enum DecisionKind { APPROVE, REJECT, NEEDS_INFORMATION }
	public record Status(String requestId, long generation, long revision, GuardianTeamRequest.State state,
		String noticeVersion, String noticeDigest, Instant requestExpiresAt, Instant contactEraseDueAt,
		Instant webDeclaredAt, Instant explicitResponseAt, Instant approvedUntil,
		boolean currentNotice, boolean serviceApproved, boolean externalAiApproved, GuardianTeamRequest.Reason reason) { }
	public record Link(String url, Instant expiresAt, boolean replayed, Status status) {
		@Override public String toString() { return "GuardianTeamLink[REDACTED]"; }
	}
	public enum Requirement { REQUIRED, NOT_REQUIRED, BIRTHDATE_REQUIRED }
	public record Entry(Requirement requirement, boolean teamReviewAvailable, boolean canStartRequest,
		String replyChannel, View request) { }
	public record View(Status status, String noticeUrl, List<String> requiredScopes, String optionalAiScope,
		String replyChannel, Map<String, String> forms) { }
	public record Detail(Status status, Long userId, String guardianName, String guardianContact, GuardianTeamRequest.ContactOrigin contactOrigin,
		Instant generationStartedAt, Set<String> declaredScopes,
		@Schema(description = "현재 신청 고지와 전체 정책 설정이 일치할 때의 필수 선택 범위. stale이면 빈 배열이며 선언·승인을 의미하지 않습니다.") List<String> requiredScopes,
		@Schema(description = "현재 신청 고지와 전체 정책 설정이 일치하고 선택 AI가 설정된 경우의 범위. 미설정·stale은 null이며 승인을 의미하지 않습니다.", allowableValues = {"EXTERNAL_AI"}, nullable = true) String optionalAiScope,
		String replyChannel,
		GuardianTeamRequest.Relationship relationship, GuardianTeamRequest.ConfirmationMethod confirmationMethod,
		String evidenceReference, List<Event> events, Map<String, String> forms) {
		@Override public String toString() { return "GuardianTeamDetail[REDACTED]"; }
	}
	public record Event(long generation, long revision, String type, GuardianTeamRequest.State state, Long actorId, Instant at) { }
	public record ListResponse(List<Status> content, int page, int size, long totalElements, int totalPages) { }
}
