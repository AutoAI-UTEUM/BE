package io.edupilot.session;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QaMessageRepository extends JpaRepository<QaMessage, Long> {

	List<QaMessage> findByThread_IdOrderByCreatedAtDescIdDesc(
		Long threadId,
		Pageable pageable
	);

	@Query("""
		select message
		from QaMessage message
		join message.thread thread
		join thread.session session
		where session.user.id = :studentId
		  and message.senderType = io.edupilot.session.SenderType.USER
		  and session.status in (
		    io.edupilot.session.SessionStatus.ACTIVE,
		    io.edupilot.session.SessionStatus.COMPLETED
		  )
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = session.material
		      and link.week.classroom.id = :classroomId
		      and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  )
		order by message.createdAt, message.id
		""")
	List<QaMessage> findReportQuestions(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber
	);

	@Query("""
		select session.material.id as materialId,
		       thread.pageNumber as pageNumber,
		       count(message.id) as questionCount,
		       sum(case when message.createdAt >= :since then 1 else 0 end)
		         as questionCountLast7Days
		from QaMessage message
		join message.thread thread
		join thread.session session
		join ClassroomMember member
		  on member.user = session.user
		where member.classroom.id = :classroomId
		  and session.material.id in :materialIds
		  and session.status in :statuses
		  and message.senderType = io.edupilot.session.SenderType.USER
		group by session.material.id, thread.pageNumber
		order by session.material.id, thread.pageNumber
		""")
	List<ClassroomQuestionCount> findClassroomQuestionCounts(
		@Param("classroomId") Long classroomId,
		@Param("materialIds") Collection<Long> materialIds,
		@Param("statuses") Collection<SessionStatus> statuses,
		@Param("since") Instant since
	);

	@Query("""
		select session.user.id as studentId,
		       count(message.id) as questionCount
		from QaMessage message
		join message.thread thread
		join thread.session session
		join ClassroomMember member
		  on member.user = session.user
		where member.classroom.id = :classroomId
		  and session.user.id in :studentIds
		  and session.material.id in :materialIds
		  and session.status in :statuses
		  and message.senderType = io.edupilot.session.SenderType.USER
		  and message.createdAt >= :since
		group by session.user.id
		""")
	List<StudentQuestionCount> findRecentQuestionCountsByStudentIds(
		@Param("classroomId") Long classroomId,
		@Param("studentIds") Collection<Long> studentIds,
		@Param("materialIds") Collection<Long> materialIds,
		@Param("statuses") Collection<SessionStatus> statuses,
		@Param("since") Instant since
	);

	interface ClassroomQuestionCount {
		Long getMaterialId();
		Integer getPageNumber();
		Long getQuestionCount();
		Long getQuestionCountLast7Days();
	}

	interface StudentQuestionCount {
		Long getStudentId();
		Long getQuestionCount();
	}
}
