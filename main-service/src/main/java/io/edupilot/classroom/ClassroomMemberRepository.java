package io.edupilot.classroom;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassroomMemberRepository
	extends JpaRepository<ClassroomMember, Long> {

	boolean existsByClassroom_IdAndUser_Id(Long classroomId, Long userId);

	long countByClassroom_Id(Long classroomId);

	@EntityGraph(attributePaths = "user")
	Page<ClassroomMember> findByClassroom_Id(Long classroomId, Pageable pageable);

	@EntityGraph(attributePaths = "user")
	Optional<ClassroomMember> findByClassroom_IdAndUser_Id(
		Long classroomId,
		Long userId
	);
}
