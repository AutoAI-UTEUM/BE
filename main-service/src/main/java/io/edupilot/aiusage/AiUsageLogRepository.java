package io.edupilot.aiusage;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, Long> {

	long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, Instant since);
}
