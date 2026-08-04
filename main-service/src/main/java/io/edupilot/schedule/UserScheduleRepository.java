package io.edupilot.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserScheduleRepository extends JpaRepository<UserSchedule, Long> {

	Optional<UserSchedule> findByIdAndUser_Id(Long id, Long userId);

	@Query("""
		select schedule
		from UserSchedule schedule
		where schedule.user.id = :userId
		  and schedule.startsAt < :toExclusive
		  and schedule.endsAt >= :fromInclusive
		order by schedule.startsAt asc, schedule.id asc
		""")
	List<UserSchedule> findVisibleInRange(
		@Param("userId") Long userId,
		@Param("fromInclusive") Instant fromInclusive,
		@Param("toExclusive") Instant toExclusive
	);
}
