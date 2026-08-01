package io.edupilot.classroom;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

	boolean existsByInviteCode(String inviteCode);

	Optional<Classroom> findByInviteCode(String inviteCode);

	@EntityGraph(attributePaths = "instructor")
	@Query(
		value = """
			select distinct classroom
			from Classroom classroom
			left join ClassroomMember member
			  on member.classroom = classroom
			where (classroom.instructor.id = :userId or member.user.id = :userId)
			  and (:status is null or classroom.status = :status)
			  and (:query is null or lower(classroom.name) like lower(concat('%', :query, '%')))
			""",
		countQuery = """
			select count(distinct classroom.id)
			from Classroom classroom
			left join ClassroomMember member
			  on member.classroom = classroom
			where (classroom.instructor.id = :userId or member.user.id = :userId)
			  and (:status is null or classroom.status = :status)
			  and (:query is null or lower(classroom.name) like lower(concat('%', :query, '%')))
			"""
	)
	Page<Classroom> findVisibleByUserId(
		@Param("userId") Long userId,
		@Param("status") ClassroomStatus status,
		@Param("query") String query,
		Pageable pageable
	);

	@EntityGraph(attributePaths = "instructor")
	@Query("""
		select distinct classroom
		from Classroom classroom
		left join ClassroomMember member
		  on member.classroom = classroom
		where classroom.instructor.id = :userId
		   or member.user.id = :userId
		""")
	List<Classroom> findAllVisibleByUserId(@Param("userId") Long userId);

	@EntityGraph(attributePaths = "instructor")
	Optional<Classroom> findWithInstructorById(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select classroom from Classroom classroom join fetch classroom.instructor where classroom.id = :id")
	Optional<Classroom> findByIdForUpdate(@Param("id") Long id);
}
