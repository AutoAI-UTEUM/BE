package io.edupilot.guardian;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface GuardianWebRequestRepository extends JpaRepository<GuardianWebRequest, String> {
	@Query("select request.user.id from GuardianWebRequest request where request.tokenHash = :hash")
	Optional<Long> ownerOfToken(@Param("hash") String hash);
	@Query("select request.user.id from GuardianWebRequest request where request.id = :id")
	Optional<Long> ownerOfId(@Param("id") String id);
	Optional<GuardianWebRequest> findByTokenHash(String hash);
	// Under MySQL REPEATABLE_READ, a locking read sees the committed row after the User lock wait.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from GuardianWebRequest request where request.tokenHash = :hash")
	Optional<GuardianWebRequest> findByTokenHashForUpdate(@Param("hash") String hash);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from GuardianWebRequest request where request.id = :id")
	Optional<GuardianWebRequest> findByIdForUpdate(@Param("id") String id);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from GuardianWebRequest request where request.user.id = :userId order by request.issuedAt desc, request.id desc")
	List<GuardianWebRequest> findByUserIdForUpdate(@Param("userId") Long userId);
	List<GuardianWebRequest> findByUser_IdOrderByIssuedAtDescIdDesc(Long userId);
	long countByUser_IdAndIssuedAtAfter(Long userId, Instant cutoff);
	@Query("select request.id from GuardianWebRequest request where (request.state = io.edupilot.guardian.GuardianWebRequest$State.AWAITING_CONSENT and request.expiresAt <= :now) or (request.state in (io.edupilot.guardian.GuardianWebRequest$State.SENDING, io.edupilot.guardian.GuardianWebRequest$State.PHONE_PENDING, io.edupilot.guardian.GuardianWebRequest$State.VERIFYING) and request.phoneExpiresAt <= :now) order by request.issuedAt, request.id")
	List<String> expiredIds(@Param("now") Instant now, Pageable pageable);
}
