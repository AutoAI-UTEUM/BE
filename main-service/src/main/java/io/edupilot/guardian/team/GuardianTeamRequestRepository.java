package io.edupilot.guardian.team;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface GuardianTeamRequestRepository extends JpaRepository<GuardianTeamRequest, String> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from GuardianTeamRequest r where r.user.id = :userId")
	Optional<GuardianTeamRequest> findByUserForUpdate(@Param("userId") Long userId);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from GuardianTeamRequest r where r.id = :id")
	Optional<GuardianTeamRequest> findByIdForUpdate(@Param("id") String id);
	@Query("select r.user.id from GuardianTeamRequest r where r.id = :id")
	Optional<Long> ownerOfId(@Param("id") String id);
	@Query("select r.user.id from GuardianTeamRequest r where r.tokenHash = :hash or r.consumedTokenHash = :hash")
	Optional<Long> ownerOfToken(@Param("hash") String hash);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from GuardianTeamRequest r where r.tokenHash = :hash or r.consumedTokenHash = :hash")
	Optional<GuardianTeamRequest> findByTokenForUpdate(@Param("hash") String hash);
	@Query("select r from GuardianTeamRequest r order by r.generationStartedAt, r.id")
	Page<GuardianTeamRequest> listOrdered(Pageable pageable);
	@Query("""
		select r.id from GuardianTeamRequest r where
		(r.state in (io.edupilot.guardian.team.GuardianTeamRequest.State.AWAITING_CONSENT,
		 io.edupilot.guardian.team.GuardianTeamRequest.State.DECLARED,
		 io.edupilot.guardian.team.GuardianTeamRequest.State.REVIEW_PENDING,
		 io.edupilot.guardian.team.GuardianTeamRequest.State.NEEDS_INFORMATION)
		 and (r.requestExpiresAt <= :now or r.unconfirmedEraseDueAt <= :now))
		or (r.state = io.edupilot.guardian.team.GuardianTeamRequest.State.APPROVED and r.approvedUntil <= :now)
		or (r.evidenceErasedAt is null and r.evidenceEraseDueAt <= :now)
		order by r.requestExpiresAt, r.id
		""")
	List<String> dueIds(@Param("now") Instant now, Pageable pageable);
}
