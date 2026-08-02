package io.edupilot.classroom;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClassroomWeekRepository extends JpaRepository<ClassroomWeek, Long> {

	List<ClassroomWeek> findByClassroom_IdOrderByWeekNumberAsc(Long classroomId);

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
}
