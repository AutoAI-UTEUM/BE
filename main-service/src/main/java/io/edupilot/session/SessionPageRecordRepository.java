package io.edupilot.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class SessionPageRecordRepository {

	private static final String UPSERT_EXPLAINED_PAGE = """
		INSERT INTO session_page_records (
		    session_id,
		    page_number,
		    explained_at,
		    created_at,
		    updated_at
		) VALUES (?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE
		    explained_at = ?,
		    updated_at = ?
		""";

	private static final String COUNT_BY_SESSION = """
		SELECT COUNT(*)
		FROM session_page_records
		WHERE session_id = ?
		""";

	private static final String COUNT_BY_USER_AND_MATERIAL = """
		SELECT COUNT(DISTINCT record.page_number)
		FROM session_page_records record
		JOIN learning_sessions session
		  ON session.id = record.session_id
		WHERE session.user_id = ?
		  AND session.material_id = ?
		  AND session.status IN ('ACTIVE', 'COMPLETED')
		""";

	private static final String CLASSROOM_PROGRESS_COUNTS = """
		SELECT member.user_id,
		       session.material_id,
		       COUNT(DISTINCT record.page_number) AS explained_page_count
		FROM classroom_members member
		JOIN learning_sessions session
		  ON session.user_id = member.user_id
		JOIN session_page_records record
		  ON record.session_id = session.id
		WHERE member.classroom_id = ?
		  AND session.material_id IN (%s)
		  AND session.status IN ('ACTIVE', 'COMPLETED')
		GROUP BY member.user_id, session.material_id
		""";

	private final JdbcTemplate jdbcTemplate;

	public SessionPageRecordRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public void upsertExplainedPage(
		Long sessionId,
		int pageNumber,
		Instant explainedAt
	) {
		Timestamp timestamp = Timestamp.from(explainedAt);
		jdbcTemplate.update(
			UPSERT_EXPLAINED_PAGE,
			sessionId,
			pageNumber,
			timestamp,
			timestamp,
			timestamp,
			timestamp,
			timestamp
		);
	}

	public long countBySessionId(Long sessionId) {
		Long count = jdbcTemplate.queryForObject(
			COUNT_BY_SESSION,
			Long.class,
			sessionId
		);
		return count == null ? 0 : count;
	}

	public long countDistinctByUserIdAndMaterialId(
		Long userId,
		Long materialId
	) {
		Long count = jdbcTemplate.queryForObject(
			COUNT_BY_USER_AND_MATERIAL,
			Long.class,
			userId,
			materialId
		);
		return count == null ? 0 : count;
	}

	public List<UserMaterialProgressCount> findClassroomProgressCounts(
		Long classroomId,
		Collection<Long> materialIds
	) {
		if (materialIds.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(
			", ",
			Collections.nCopies(materialIds.size(), "?")
		);
		Object[] arguments = new Object[materialIds.size() + 1];
		arguments[0] = classroomId;
		int index = 1;
		for (Long materialId : materialIds) {
			arguments[index++] = materialId;
		}
		return jdbcTemplate.query(
			CLASSROOM_PROGRESS_COUNTS.formatted(placeholders),
			(rs, rowNum) -> new UserMaterialProgressCount(
				rs.getLong("user_id"),
				rs.getLong("material_id"),
				rs.getLong("explained_page_count")
			),
			arguments
		);
	}

	public record UserMaterialProgressCount(
		Long userId,
		Long materialId,
		long explainedPageCount
	) {
	}
}
