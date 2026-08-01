package io.edupilot.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassroomMemberRepository
	extends JpaRepository<ClassroomMember, Long> {

	boolean existsByClassroom_IdAndUser_Id(Long classroomId, Long userId);

	long countByClassroom_Id(Long classroomId);
}
