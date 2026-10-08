package io.edupilot.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
	@Query("select token.user.id from EmailVerificationToken token where token.tokenHash = :hash")
	Optional<Long> findUserIdByTokenHash(@Param("hash") String hash);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select token from EmailVerificationToken token where token.tokenHash = :hash")
	Optional<EmailVerificationToken> findByTokenHashForUpdate(@Param("hash") String hash);
	@Modifying
	@Query("update EmailVerificationToken token set token.usedAt = :now where token.user.id = :userId and token.usedAt is null")
	int invalidateUnused(@Param("userId") Long userId, @Param("now") Instant now);
	@Modifying
	@Query("delete from EmailVerificationToken token where token.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);
}
