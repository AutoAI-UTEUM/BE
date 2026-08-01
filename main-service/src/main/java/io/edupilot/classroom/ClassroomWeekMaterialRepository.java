package io.edupilot.classroom;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialProcessingStatus;
import io.edupilot.material.MaterialStatus;

public interface ClassroomWeekMaterialRepository
	extends JpaRepository<ClassroomWeekMaterial, Long> {

	@EntityGraph(attributePaths = "material")
	List<ClassroomWeekMaterial> findByWeek_IdOrderByAddedAtAscIdAsc(Long weekId);

	boolean existsByWeek_IdAndMaterial_Id(Long weekId, Long materialId);

	boolean existsByMaterial_Id(Long materialId);

	Optional<ClassroomWeekMaterial> findByWeek_IdAndMaterial_Id(
		Long weekId,
		Long materialId
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	long deleteByWeek_Id(Long weekId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	long deleteByWeek_IdAndMaterial_Id(Long weekId, Long materialId);

	@Query("""
		select case when count(link) > 0 then true else false end
		from ClassroomWeekMaterial link
		join ClassroomMember member
		  on member.classroom = link.week.classroom
		where link.material.id = :materialId
		  and member.user.id = :userId
		  and (link.week.releaseAt is null or link.week.releaseAt <= :now)
		""")
	boolean existsReleasedAccess(
		@Param("userId") Long userId,
		@Param("materialId") Long materialId,
		@Param("now") Instant now
	);

	@Query("""
		select distinct link.material
		from ClassroomWeekMaterial link
		where link.week.classroom.id = :classroomId
		  and (link.week.releaseAt is null or link.week.releaseAt <= :now)
		  and link.material.status = :materialStatus
		  and link.material.processingStatus = :processingStatus
		""")
	List<LearningMaterial> findDistinctReleasedReadyMaterials(
		@Param("classroomId") Long classroomId,
		@Param("now") Instant now,
		@Param("materialStatus") MaterialStatus materialStatus,
		@Param("processingStatus") MaterialProcessingStatus processingStatus
	);
}
