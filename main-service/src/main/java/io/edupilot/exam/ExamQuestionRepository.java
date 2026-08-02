package io.edupilot.exam;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {

	List<ExamQuestion> findByExam_IdOrderByQuestionNo(Long examId);

	void deleteByExam_Id(Long examId);

	long countByExam_Id(Long examId);
}
