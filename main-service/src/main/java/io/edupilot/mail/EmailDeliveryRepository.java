package io.edupilot.mail;

import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, Long> {

	@Query("""
		select count(d) from EmailDelivery d
		where d.recipient = :recipient and d.createdAt >= :since and d.id <= :id
		and (d.status in (io.edupilot.mail.EmailDeliveryStatus.QUEUED,
		                  io.edupilot.mail.EmailDeliveryStatus.SENT)
		     or (d.status = io.edupilot.mail.EmailDeliveryStatus.FAILED
		         and d.errorSummary <> 'DISABLED'))
		""")
	long countRecipientQuota(
		@Param("recipient") String recipient,
		@Param("since") Instant since,
		@Param("id") Long id
	);

	@Query("""
		select count(d) from EmailDelivery d
		where d.createdAt >= :since and d.id <= :id
		and (d.status in (io.edupilot.mail.EmailDeliveryStatus.QUEUED,
		                  io.edupilot.mail.EmailDeliveryStatus.SENT)
		     or (d.status = io.edupilot.mail.EmailDeliveryStatus.FAILED
		         and d.errorSummary <> 'DISABLED'))
		""")
	long countDailyQuota(@Param("since") Instant since, @Param("id") Long id);

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
