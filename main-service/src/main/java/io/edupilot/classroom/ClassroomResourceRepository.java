package io.edupilot.classroom;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClassroomResourceRepository
	extends JpaRepository<ClassroomResource, Long> {

	Page<ClassroomResource> findByClassroom_Id(
		Long classroomId,
		Pageable pageable
	);

	Page<ClassroomResource> findByClassroom_IdAndWeekNumber(
		Long classroomId,
		Integer weekNumber,
		Pageable pageable
	);

	@Query("""
		select resource
		from ClassroomResource resource
		join fetch resource.classroom
		where resource.id = :resourceId
		""")
	Optional<ClassroomResource> findWithClassroom(
		@Param("resourceId") Long resourceId
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select resource
		from ClassroomResource resource
		join fetch resource.classroom
		where resource.id = :resourceId
		""")
	Optional<ClassroomResource> findForUpdate(
		@Param("resourceId") Long resourceId
	);
}
