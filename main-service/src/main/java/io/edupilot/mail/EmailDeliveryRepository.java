package io.edupilot.mail;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {

	// Audit inserts commit independently and can be newer than the caller's REPEATABLE READ snapshot.
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	@Query("select delivery.type from EmailDelivery delivery where delivery.id = :id")
	Optional<EmailDeliveryType> findCommittedTypeById(@Param("id") Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select delivery from EmailDelivery delivery where delivery.id = :id")
	Optional<EmailDelivery> findForUpdate(@Param("id") Long id);

	@Query("""
		select d from EmailDelivery d
		where (:from is null or d.createdAt >= :from)
		and (:to is null or d.createdAt < :to)
		and (:status is null or d.status = :status)
		""")
	Page<EmailDelivery> findForAdmin(
		@Param("from") Instant from,
		@Param("to") Instant to,
		@Param("status") EmailDeliveryStatus status,
		Pageable pageable
	);
}
