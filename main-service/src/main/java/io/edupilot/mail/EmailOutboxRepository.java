package io.edupilot.mail;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select job from EmailOutbox job where job.id = :id")
	Optional<EmailOutbox> findForUpdate(@Param("id") Long id);

	@Query("""
		select job.id from EmailOutbox job where
		(job.status in (io.edupilot.mail.EmailOutboxStatus.READY, io.edupilot.mail.EmailOutboxStatus.RETRY,
		 io.edupilot.mail.EmailOutboxStatus.CLAIMED) and job.expiresAt <= :now)
		or (job.status in (io.edupilot.mail.EmailOutboxStatus.CLAIMED, io.edupilot.mail.EmailOutboxStatus.SENDING)
		 and job.leaseUntil <= :now)
		order by job.expiresAt, job.id
		""")
	List<Long> findCleanupIds(@Param("now") Instant now, Pageable pageable);

	@Query("""
		select job.id from EmailOutbox job where
		job.status in (io.edupilot.mail.EmailOutboxStatus.READY, io.edupilot.mail.EmailOutboxStatus.RETRY)
		and job.nextAttemptAt <= :now and job.expiresAt > :now
		order by job.nextAttemptAt, job.id
		""")
	List<Long> findDispatchableIds(@Param("now") Instant now, Pageable pageable);

	@Query("""
		select job.id from EmailOutbox job join job.delivery delivery where
		job.id in :ids and delivery.recipient = :recipient
		and job.attemptCount = 0 and delivery.attemptCount = 0
		and job.status in (io.edupilot.mail.EmailOutboxStatus.READY, io.edupilot.mail.EmailOutboxStatus.RETRY)
		and job.nextAttemptAt <= :now and job.expiresAt > :now
		order by job.nextAttemptAt, job.id
		""")
	List<Long> findTrialDispatchableIds(@Param("now") Instant now, @Param("ids") java.util.Set<Long> ids,
		@Param("recipient") String recipient, Pageable pageable);
}
