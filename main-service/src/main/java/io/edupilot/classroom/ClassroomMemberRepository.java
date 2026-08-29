package io.edupilot.classroom;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClassroomMemberRepository
	extends JpaRepository<ClassroomMember, Long> {

	boolean existsByClassroom_IdAndUser_Id(Long classroomId, Long userId);

	long countByClassroom_Id(Long classroomId);

	@Query("select member.user.id from ClassroomMember member where member.classroom.id = :classroomId order by member.user.id")
	List<Long> findUserIdsByClassroomId(@Param("classroomId") Long classroomId);

	@EntityGraph(attributePaths = "user")
	Page<ClassroomMember> findByClassroom_Id(Long classroomId, Pageable pageable);

	@EntityGraph(attributePaths = "user")
	List<ClassroomMember> findByClassroom_Id(Long classroomId, Sort sort);

	@EntityGraph(attributePaths = "user")
	Optional<ClassroomMember> findByClassroom_IdAndUser_Id(
		Long classroomId,
		Long userId
	);

	@Query("""
		select member.classroom.id as classroomId,
		       count(member.id) as memberCount
		from ClassroomMember member
		where member.classroom.id in :classroomIds
		group by member.classroom.id
		""")
	List<ClassroomMemberCount> countByClassroomIds(
		@Param("classroomIds") Collection<Long> classroomIds
	);

	interface ClassroomMemberCount {
		Long getClassroomId();
		Long getMemberCount();
	}
}
