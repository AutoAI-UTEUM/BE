package io.edupilot.report;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportCriterionRepository extends JpaRepository<ReportCriterion, Long> {

	List<ReportCriterion> findByClassroom_IdAndActiveTrueOrderByCriterionKeyAscVersionDesc(
		Long classroomId
	);

	Optional<ReportCriterion> findByIdAndClassroom_Id(Long id, Long classroomId);

	List<ReportCriterion> findByClassroom_IdAndCriterionKeyOrderByVersionDesc(
		Long classroomId,
		String criterionKey
	);

	boolean existsByClassroom_IdAndCriterionKey(Long classroomId, String criterionKey);

	long countByClassroom_IdAndActiveTrue(Long classroomId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		delete from ReportCriterion criterion
		where criterion.classroom.id = :classroomId
		  and criterion.criterionKey = :criterionKey
		""")
	int deleteByClassroom_IdAndCriterionKey(
		@Param("classroomId") Long classroomId,
		@Param("criterionKey") String criterionKey
	);

	@Query("""
		select distinct criterion.criterionKey
		from ReportCriterion criterion
		where criterion.classroom.id = :classroomId
		order by criterion.criterionKey
		""")
	List<String> findDistinctCriterionKeysByClassroomId(
		@Param("classroomId") Long classroomId
	);
}
