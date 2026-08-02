package io.edupilot.exam;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamAnswerRepository extends JpaRepository<ExamAnswer, Long> {

	List<ExamAnswer> findBySubmission_IdOrderByQuestion_Id(Long submissionId);
}
