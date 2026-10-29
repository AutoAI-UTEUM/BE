package io.edupilot.guardian.team.mail;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface GuardianTeamMailBindingRepository extends JpaRepository<GuardianTeamMailBinding, Long> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select binding from GuardianTeamMailBinding binding where binding.requestId = :requestId "
		+ "order by binding.deliveryId")
	List<GuardianTeamMailBinding> findForRequestUpdate(@Param("requestId") String requestId);
}
