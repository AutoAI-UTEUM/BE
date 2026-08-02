package io.edupilot.exam;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamRepository extends JpaRepository<Exam, Long> {

	@EntityGraph(attributePaths = "classroom")
	Optional<Exam> findWithClassroomById(Long id);
}
