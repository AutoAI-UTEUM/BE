package io.edupilot.exam;

import java.util.Optional;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ExamRepository extends JpaRepository<Exam, Long> {

	@EntityGraph(attributePaths = "classroom")
	Optional<Exam> findWithClassroomById(Long id);

	@EntityGraph(attributePaths = "classroom")
	Page<Exam> findByClassroom_IdAndStatus(
		Long classroomId,
		ExamStatus status,
		Pageable pageable
	);

	@EntityGraph(attributePaths = "classroom")
	Page<Exam> findByClassroom_Id(Long classroomId, Pageable pageable);

	@EntityGraph(attributePaths = "classroom")
	Page<Exam> findByClassroom_IdAndStatusIn(
		Long classroomId,
		Set<ExamStatus> statuses,
		Pageable pageable
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select exam from Exam exam join fetch exam.classroom where exam.id = :id")
	Optional<Exam> findByIdForUpdate(@Param("id") Long id);
}
