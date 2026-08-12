package io.edupilot.classroom;

import java.util.Collection;
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

	@EntityGraph(attributePaths = "material")
	@Query("""
		select link
		from ClassroomWeekMaterial link
		where link.week.id in :weekIds
		order by link.week.id, link.addedAt, link.id
		""")
	List<ClassroomWeekMaterial> findByWeekIds(
		@Param("weekIds") Collection<Long> weekIds
	);

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

	@EntityGraph(attributePaths = "week")
	@Query("""
		select link
		from ClassroomWeekMaterial link
		join ClassroomMember member
		  on member.classroom = link.week.classroom
		where link.material.id = :materialId
		  and member.user.id = :userId
		""")
	List<ClassroomWeekMaterial> findAccessCandidates(
		@Param("userId") Long userId,
		@Param("materialId") Long materialId
	);

	@EntityGraph(attributePaths = {"week", "material"})
	@Query("""
		select link
		from ClassroomWeekMaterial link
		where link.week.classroom.id = :classroomId
		  and link.material.status = :materialStatus
		  and link.material.processingStatus = :processingStatus
		order by link.id
		""")
	List<ClassroomWeekMaterial> findReadyMaterialCandidates(
		@Param("classroomId") Long classroomId,
		@Param("materialStatus") MaterialStatus materialStatus,
		@Param("processingStatus") MaterialProcessingStatus processingStatus
	);

	@EntityGraph(attributePaths = {"week", "material"})
	@Query("""
		select link
		from ClassroomWeekMaterial link
		where link.week.classroom.id = :classroomId
		  and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  and link.material.status = :materialStatus
		  and link.material.processingStatus = :processingStatus
		order by link.material.id, link.id
		""")
	List<ClassroomWeekMaterial> findReportMaterialCandidates(
		@Param("classroomId") Long classroomId,
		@Param("weekNumber") Integer weekNumber,
		@Param("materialStatus") MaterialStatus materialStatus,
		@Param("processingStatus") MaterialProcessingStatus processingStatus
	);

@Query("""
		select link.week.classroom.id as classroomId,
		       count(distinct link.material.id) as materialCount
		from ClassroomWeekMaterial link
		where link.week.classroom.id in :classroomIds
		group by link.week.classroom.id
		""")
	List<ClassroomMaterialCount> countDistinctMaterialsByClassroomIds(
		@Param("classroomIds") Collection<Long> classroomIds
	);

	interface ClassroomMaterialCount {
		Long getClassroomId();
		Long getMaterialCount();
	}

	default boolean existsAccess(Long userId, Long materialId) {
		return !findAccessCandidates(userId, materialId).isEmpty();
	}

	default List<LearningMaterial> findDistinctReadyMaterials(
		Long classroomId,
		MaterialStatus materialStatus,
		MaterialProcessingStatus processingStatus
	) {
		return distinctMaterials(findReadyMaterialCandidates(
			classroomId,
			materialStatus,
			processingStatus
		));
	}

	default List<LearningMaterial> findReportMaterials(
		Long classroomId,
		Integer weekNumber,
		MaterialStatus materialStatus,
		MaterialProcessingStatus processingStatus
	) {
		return distinctMaterials(findReportMaterialCandidates(
			classroomId,
			weekNumber,
			materialStatus,
			processingStatus
		));
	}

	default boolean existsVisibleAccess(
		Long userId,
		Long materialId,
		Instant now
	) {
		return findAccessCandidates(userId, materialId).stream()
			.anyMatch(link -> link.getWeek().isVisibleToLearner(now));
	}

	default List<LearningMaterial> findDistinctVisibleReadyMaterials(
		Long classroomId,
		Instant now,
		MaterialStatus materialStatus,
		MaterialProcessingStatus processingStatus
	) {
		return visibleMaterials(
			findReadyMaterialCandidates(
				classroomId,
				materialStatus,
				processingStatus
			),
			now
		);
	}

	default List<LearningMaterial> findVisibleReportMaterials(
		Long classroomId,
		Integer weekNumber,
		Instant now,
		MaterialStatus materialStatus,
		MaterialProcessingStatus processingStatus
	) {
		return visibleMaterials(
			findReportMaterialCandidates(
				classroomId,
				weekNumber,
				materialStatus,
				processingStatus
			),
			now
		);
	}

	private static List<LearningMaterial> visibleMaterials(
		List<ClassroomWeekMaterial> candidates,
		Instant now
	) {
		return candidates.stream()
			.filter(link -> link.getWeek().isVisibleToLearner(now))
			.map(ClassroomWeekMaterial::getMaterial)
			.distinct()
			.toList();
	}

	private static List<LearningMaterial> distinctMaterials(
		List<ClassroomWeekMaterial> candidates
	) {
		return candidates.stream()
			.map(ClassroomWeekMaterial::getMaterial)
			.distinct()
			.toList();
	}
}
