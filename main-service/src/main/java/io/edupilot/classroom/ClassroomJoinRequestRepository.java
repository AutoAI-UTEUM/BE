package io.edupilot.classroom;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClassroomJoinRequestRepository
	extends JpaRepository<ClassroomJoinRequest, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select request
		from ClassroomJoinRequest request
		where request.classroom.id = :classroomId
		  and request.user.id = :userId
		""")
	Optional<ClassroomJoinRequest> findByClassroomAndUserForUpdate(
		@Param("classroomId") Long classroomId,
		@Param("userId") Long userId
	);

	@EntityGraph(attributePaths = {"classroom", "user"})
	Page<ClassroomJoinRequest> findByUser_Id(Long userId, Pageable pageable);

	@EntityGraph(attributePaths = "user")
	Page<ClassroomJoinRequest> findByClassroom_IdAndStatus(
		Long classroomId,
		ClassroomJoinRequestStatus status,
		Pageable pageable
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select request
		from ClassroomJoinRequest request
		join fetch request.classroom classroom
		join fetch classroom.instructor
		join fetch request.user
		where request.id = :requestId
		  and classroom.id = :classroomId
		""")
	Optional<ClassroomJoinRequest> findForUpdate(
		@Param("classroomId") Long classroomId,
		@Param("requestId") Long requestId
	);

	long countByClassroom_IdAndStatus(
		Long classroomId,
		ClassroomJoinRequestStatus status
	);
}
