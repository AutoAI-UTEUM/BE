package io.edupilot.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select session from AuthSession session where session.id = :sessionId")
	Optional<AuthSession> findByIdForUpdate(@Param("sessionId") Long sessionId);

	@Modifying
	@Query("""
		update AuthSession session
		set session.revokedAt = :revokedAt
		where session.user.id = :userId
		  and session.revokedAt is null
		""")
	int revokeAllActiveByUserId(
		@Param("userId") Long userId,
		@Param("revokedAt") Instant revokedAt
	);
}
