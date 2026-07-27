package io.edupilot.session;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface QaThreadRepository extends JpaRepository<QaThread, Long> {

	Optional<QaThread> findTopBySession_IdAndStatusOrderByUpdatedAtDescIdDesc(
		Long sessionId,
		QaThreadStatus status
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select thread
		from QaThread thread
		where thread.id = :threadId
		  and thread.session.id = :sessionId
		  and thread.status = io.edupilot.session.QaThreadStatus.ACTIVE
		""")
	Optional<QaThread> findActiveForUpdate(
		@Param("threadId") Long threadId,
		@Param("sessionId") Long sessionId
	);
}
