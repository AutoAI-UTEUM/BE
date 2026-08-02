package io.edupilot.classroom;

import java.time.Instant;
import java.util.Collection;
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

public interface ClassroomNoticeRepository
	extends JpaRepository<ClassroomNotice, Long> {

	Page<ClassroomNotice> findByClassroom_Id(Long classroomId, Pageable pageable);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select notice
		from ClassroomNotice notice
		where notice.id = :noticeId
		  and notice.classroom.id = :classroomId
		""")
	Optional<ClassroomNotice> findForUpdate(
		@Param("classroomId") Long classroomId,
		@Param("noticeId") Long noticeId
	);

	@EntityGraph(attributePaths = "classroom")
	@Query("""
		select notice
		from ClassroomNotice notice
		where notice.classroom.id in :classroomIds
		  and notice.publishedAt >= :from
		  and notice.publishedAt < :toExclusive
		""")
	List<ClassroomNotice> findScheduleNotices(
		@Param("classroomIds") Collection<Long> classroomIds,
		@Param("from") Instant from,
		@Param("toExclusive") Instant toExclusive
	);
}
