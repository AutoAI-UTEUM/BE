package io.edupilot.guardian.team;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianTeamEventRepository extends JpaRepository<GuardianTeamEvent, String> {
	long countByRequestIdAndGeneration(String requestId, long generation);
	long countByRequestIdAndEventTypeAndRecordedAtAfter(String requestId, String eventType, Instant after);
	List<GuardianTeamEvent> findByRequestIdOrderByRecordedAtAscIdAsc(String requestId, Pageable pageable);
	@Modifying @Query("delete from GuardianTeamEvent e where e.requestId = :id and e.eraseDueAt <= :now")
	int eraseDue(@Param("id") String id, @Param("now") Instant now);
	@Modifying @Query("update GuardianTeamEvent e set e.eraseDueAt = :deadline where e.requestId = :id and e.generation = :generation")
	int setGenerationDeadline(@Param("id") String id, @Param("generation") long generation, @Param("deadline") Instant deadline);
	@Query("select distinct e.requestId from GuardianTeamEvent e where e.eraseDueAt <= :now")
	List<String> dueRequestIds(@Param("now") Instant now, Pageable pageable);
}
