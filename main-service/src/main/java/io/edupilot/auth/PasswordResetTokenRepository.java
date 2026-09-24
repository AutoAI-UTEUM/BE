package io.edupilot.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.LockModeType;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	@Query("select token.user.id from PasswordResetToken token where token.tokenHash = :tokenHash")
	Optional<Long> findUserIdByTokenHash(@Param("tokenHash") String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from PasswordResetToken token where token.tokenHash = :tokenHash")
	Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	@Modifying
	@Query("""
		update PasswordResetToken token set token.usedAt = :usedAt
		where token.user.id = :userId and token.usedAt is null
		""")
	int useAllUnusedByUserId(@Param("userId") Long userId, @Param("usedAt") Instant usedAt);

	@Modifying
	@Transactional
	@Query("delete from PasswordResetToken token where token.expiresAt < :cutoff")
	int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
