package io.edupilot.classroom;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClassroomWeekRepository extends JpaRepository<ClassroomWeek, Long> {

	List<ClassroomWeek> findByClassroom_IdOrderByDisplayOrderAscIdAsc(Long classroomId);

	Optional<ClassroomWeek> findByClassroom_IdAndWeekNumber(
		Long classroomId,
		int weekNumber
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select week
		from ClassroomWeek week
		where week.classroom.id = :classroomId
		  and week.weekNumber = :weekNumber
		""")
	Optional<ClassroomWeek> findForUpdate(
		@Param("classroomId") Long classroomId,
		@Param("weekNumber") int weekNumber
	);

	boolean existsByClassroom_IdAndWeekNumber(Long classroomId, int weekNumber);

	@Query("select max(week.weekNumber) from ClassroomWeek week where week.classroom.id = :classroomId")
	Integer findMaximumWeekNumber(@Param("classroomId") Long classroomId);

	@Query("select max(week.displayOrder) from ClassroomWeek week where week.classroom.id = :classroomId")
	Integer findMaximumDisplayOrder(@Param("classroomId") Long classroomId);

	@Query("""
		select week
		from ClassroomWeek week
		join fetch week.classroom
		where week.classroom.id in :classroomIds
		  and coalesce(week.releaseAt, week.createdAt) >= :from
		  and coalesce(week.releaseAt, week.createdAt) < :toExclusive
		""")
	List<ClassroomWeek> findScheduleWeeks(
		@Param("classroomIds") Collection<Long> classroomIds,
		@Param("from") Instant from,
		@Param("toExclusive") Instant toExclusive
	);
}
