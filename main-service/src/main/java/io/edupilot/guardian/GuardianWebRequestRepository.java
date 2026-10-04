package io.edupilot.guardian;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianWebRequestRepository extends JpaRepository<GuardianWebRequest, String> {
	@Query("select request.user.id from GuardianWebRequest request where request.tokenHash = :hash")
	Optional<Long> ownerOfToken(@Param("hash") String hash);
	@Query("select request.user.id from GuardianWebRequest request where request.id = :id")
	Optional<Long> ownerOfId(@Param("id") String id);
	Optional<GuardianWebRequest> findByTokenHash(String hash);
	List<GuardianWebRequest> findByUser_IdOrderByIssuedAtDescIdDesc(Long userId);
	long countByUser_IdAndIssuedAtAfter(Long userId, Instant cutoff);
	@Query("select request.id from GuardianWebRequest request where (request.state = io.edupilot.guardian.GuardianWebRequest$State.AWAITING_CONSENT and request.expiresAt <= :now) or (request.state in (io.edupilot.guardian.GuardianWebRequest$State.SENDING, io.edupilot.guardian.GuardianWebRequest$State.PHONE_PENDING, io.edupilot.guardian.GuardianWebRequest$State.VERIFYING) and request.phoneExpiresAt <= :now) order by request.issuedAt, request.id")
	List<String> expiredIds(@Param("now") Instant now, Pageable pageable);
}
