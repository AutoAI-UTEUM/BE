package io.edupilot.mail;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailSendReservationRepository extends JpaRepository<EmailSendReservation, Long> {
	boolean existsByDeliveryIdAndClaimToken(Long id, String token);

	@Query("""
		select coalesce(sum(reservation.units), 0) from EmailSendReservation reservation
		where reservation.delivery.recipient = :recipient and reservation.reservedAt >= :since
		""")
	long countRecipientReservations(@Param("recipient") String recipient, @Param("since") Instant since);

	@Query("""
		select coalesce(sum(reservation.units), 0) from EmailSendReservation reservation
		where reservation.reservedAt >= :since
		""")
	long countDailyReservations(@Param("since") Instant since);
}
