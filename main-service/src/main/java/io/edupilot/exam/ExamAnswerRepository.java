package io.edupilot.exam;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExamAnswerRepository extends JpaRepository<ExamAnswer, Long> {

	@Query("""
		select answer
		from ExamAnswer answer
		join fetch answer.question
		where answer.submission.id = :submissionId
		order by answer.question.id
		""")
	List<ExamAnswer> findBySubmission_IdOrderByQuestion_Id(
		@Param("submissionId") Long submissionId
	);
}
