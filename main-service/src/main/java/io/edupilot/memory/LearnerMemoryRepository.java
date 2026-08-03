package io.edupilot.memory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearnerMemoryRepository
	extends JpaRepository<LearnerMemory, Long> {

	Optional<LearnerMemory> findByUser_IdAndMaterial_Id(
		Long userId,
		Long materialId
	);

	@Query("""
		select distinct memory
		from LearnerMemory memory
		where memory.user.id = :studentId
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = memory.material
		      and link.week.classroom.id = :classroomId
		      and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  )
		order by memory.updatedAt, memory.id
		""")
	List<LearnerMemory> findReportMemories(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber
	);
}
