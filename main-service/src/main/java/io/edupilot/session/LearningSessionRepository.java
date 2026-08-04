package io.edupilot.session;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LearningSessionRepository
	extends JpaRepository<LearningSession, Long> {

	Optional<LearningSession> findByUser_IdAndMaterial_IdAndStatus(
		Long userId,
		Long materialId,
		SessionStatus status
	);

	@EntityGraph(attributePaths = "material")
	Page<LearningSession> findByUser_IdAndStatusIn(
		Long userId,
		Collection<SessionStatus> statuses,
		Pageable pageable
	);

	@EntityGraph(attributePaths = "material")
	Optional<LearningSession> findByIdAndUser_Id(Long id, Long userId);

	@EntityGraph(attributePaths = "material")
	Optional<LearningSession> findFirstByUser_IdAndMaterial_IdInAndStatusInOrderByUpdatedAtDescIdDesc(
		Long userId,
		Collection<Long> materialIds,
		Collection<SessionStatus> statuses
	);

	@Query("""
		select distinct session
		from LearningSession session
		join fetch session.material material
		where session.user.id = :studentId
		  and session.status in :statuses
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = material
		      and link.week.classroom.id = :classroomId
		      and (:weekNumber is null or link.week.weekNumber = :weekNumber)
		  )
		order by session.updatedAt, session.id
		""")
	List<LearningSession> findReportSessions(
		@Param("classroomId") Long classroomId,
		@Param("studentId") Long studentId,
		@Param("weekNumber") Integer weekNumber,
		@Param("statuses") Collection<SessionStatus> statuses
	);

	@Query("""
		select new io.edupilot.session.StudentLastActivity(
		  session.user.id,
		  max(session.updatedAt)
		)
		from LearningSession session
		where session.user.id in :studentIds
		  and session.status <> io.edupilot.session.SessionStatus.DELETED
		  and exists (
		    select link.id
		    from ClassroomWeekMaterial link
		    where link.material = session.material
		      and link.week.classroom.id = :classroomId
		  )
		group by session.user.id
		""")
	List<StudentLastActivity> findLastActivityByClassroomAndStudentIds(
		@Param("classroomId") Long classroomId,
		@Param("studentIds") Collection<Long> studentIds
	);

	@Query("""
		select session.material.id as materialId,
		       count(distinct session.user.id) as viewerCount
		from LearningSession session
		join ClassroomMember member
		  on member.user = session.user
		where member.classroom.id = :classroomId
		  and session.material.id in :materialIds
		  and session.status in :statuses
		group by session.material.id
		""")
	List<MaterialViewerCount> findMaterialViewerCounts(
		@Param("classroomId") Long classroomId,
		@Param("materialIds") Collection<Long> materialIds,
		@Param("statuses") Collection<SessionStatus> statuses
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select session
		from LearningSession session
		join fetch session.material
		where session.id = :sessionId
		  and session.user.id = :userId
		""")
	Optional<LearningSession> findOwnedForUpdate(
		@Param("sessionId") Long sessionId,
		@Param("userId") Long userId
	);

	boolean existsByMaterial_IdAndStatus(Long materialId, SessionStatus status);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update LearningSession session
		set session.activeTurnRequestId = :requestId,
		    session.activeTurnStartedAt = :startedAt,
		    session.updatedAt = :updatedAt,
		    session.version = session.version + 1
		where session.id = :sessionId
		  and session.user.id = :userId
		  and session.status = io.edupilot.session.SessionStatus.ACTIVE
		  and (
		    session.activeTurnRequestId is null
		    or session.activeTurnStartedAt < :staleBefore
		  )
		""")
	int claimTurn(
		@Param("sessionId") Long sessionId,
		@Param("userId") Long userId,
		@Param("requestId") String requestId,
		@Param("startedAt") Instant startedAt,
		@Param("updatedAt") Instant updatedAt,
		@Param("staleBefore") Instant staleBefore
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update LearningSession session
		set session.activeTurnRequestId = null,
		    session.activeTurnStartedAt = null,
		    session.updatedAt = :updatedAt,
		    session.version = session.version + 1
		where session.id = :sessionId
		  and session.activeTurnRequestId = :requestId
		""")
	int releaseTurn(
		@Param("sessionId") Long sessionId,
		@Param("requestId") String requestId,
		@Param("updatedAt") Instant updatedAt
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		update LearningSession session
		set session.status = io.edupilot.session.SessionStatus.DELETED,
		    session.activeTurnRequestId = null,
		    session.activeTurnStartedAt = null,
		    session.updatedAt = :updatedAt,
		    session.version = session.version + 1
		where session.user.id = :userId
		  and session.status <> io.edupilot.session.SessionStatus.DELETED
		""")
	int deleteAllByUserId(
		@Param("userId") Long userId,
		@Param("updatedAt") Instant updatedAt
	);

	interface MaterialViewerCount {
		Long getMaterialId();
		Long getViewerCount();
	}
}
