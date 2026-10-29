package io.edupilot.mail;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EmailQuotaLockRepository extends JpaRepository<EmailQuotaLock, Integer> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select quotaLock from EmailQuotaLock quotaLock where quotaLock.id = :id")
	Optional<EmailQuotaLock> findForUpdate(@Param("id") Integer id);
}
