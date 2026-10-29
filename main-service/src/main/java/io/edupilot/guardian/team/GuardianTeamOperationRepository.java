package io.edupilot.guardian.team;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuardianTeamOperationRepository extends JpaRepository<GuardianTeamOperation, String> {
	Optional<GuardianTeamOperation> findByRequestIdAndOperationKey(String requestId, String operationKey);
	@Modifying @Query("delete from GuardianTeamOperation o where o.requestId = :id and o.eraseDueAt <= :now")
	int eraseDue(@Param("id") String id, @Param("now") Instant now);
	@Modifying @Query("delete from GuardianTeamOperation o where o.requestId = :id")
	int eraseForRequest(@Param("id") String id);
}
