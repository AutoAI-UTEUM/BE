package io.edupilot.classroom;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ClassroomPermanentDeleteRepository
	extends Repository<Classroom, Long> {

	@Modifying(flushAutomatically = true)
	@Query(value = """
		delete from exam_answers
		where submission_id in (
			select submission.id
			from exam_submissions submission
			join exams exam on exam.id = submission.exam_id
			where exam.classroom_id = :classroomId
		)
		or question_id in (
			select question.id
			from exam_questions question
			join exams exam on exam.id = question.exam_id
			where exam.classroom_id = :classroomId
		)
		""", nativeQuery = true)
	int deleteExamAnswers(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		delete from exam_submissions
		where exam_id in (
			select id from exams where classroom_id = :classroomId
		)
		""", nativeQuery = true)
	int deleteExamSubmissions(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		delete from exam_questions
		where exam_id in (
			select id from exams where classroom_id = :classroomId
		)
		""", nativeQuery = true)
	int deleteExamQuestions(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from exams where classroom_id = :classroomId", nativeQuery = true)
	int deleteExams(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		delete from report_criterion_results
		where report_id in (
			select id from student_reports where classroom_id = :classroomId
		)
		""", nativeQuery = true)
	int deleteReportCriterionResults(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		update student_reports
		set previous_report_id = null
		where previous_report_id in (
			select target.id
			from (
				select id
				from student_reports
				where classroom_id = :classroomId
			) target
		)
		""", nativeQuery = true)
	int clearStudentReportPreviousReferences(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from student_reports where classroom_id = :classroomId", nativeQuery = true)
	int deleteStudentReports(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		delete from report_evidence_snapshots
		where generation_id in (
			select id from report_generations where classroom_id = :classroomId
		)
		""", nativeQuery = true)
	int deleteReportEvidenceSnapshots(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from report_generations where classroom_id = :classroomId", nativeQuery = true)
	int deleteReportGenerations(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from report_criteria where classroom_id = :classroomId", nativeQuery = true)
	int deleteReportCriteria(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from classroom_notices where classroom_id = :classroomId", nativeQuery = true)
	int deleteClassroomNotices(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = """
		delete from classroom_week_materials
		where week_id in (
			select id from classroom_weeks where classroom_id = :classroomId
		)
		""", nativeQuery = true)
	int deleteClassroomWeekMaterials(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from classroom_weeks where classroom_id = :classroomId", nativeQuery = true)
	int deleteClassroomWeeks(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from classroom_join_requests where classroom_id = :classroomId", nativeQuery = true)
	int deleteClassroomJoinRequests(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true)
	@Query(value = "delete from classroom_members where classroom_id = :classroomId", nativeQuery = true)
	int deleteClassroomMembers(@Param("classroomId") Long classroomId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "delete from classrooms where id = :classroomId", nativeQuery = true)
	int deleteClassroom(@Param("classroomId") Long classroomId);
}
