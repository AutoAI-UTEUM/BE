package io.edupilot.session;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QaMessageRepository extends JpaRepository<QaMessage, Long> {

	List<QaMessage> findByThread_IdOrderByCreatedAtDescIdDesc(
		Long threadId,
		Pageable pageable
	);
}
