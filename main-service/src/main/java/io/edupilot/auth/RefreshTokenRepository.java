package io.edupilot.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select token
		from RefreshToken token
		join fetch token.user
		where token.tokenHash = :tokenHash
		""")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	@Modifying
	@Query("""
		update RefreshToken token
		set token.revokedAt = :revokedAt
		where token.user.id = :userId
		  and token.revokedAt is null
		""")
	int revokeAllActiveByUserId(
		@Param("userId") Long userId,
		@Param("revokedAt") Instant revokedAt
	);
}
