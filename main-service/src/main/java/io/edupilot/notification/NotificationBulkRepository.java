package io.edupilot.notification;

import java.time.Instant;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

@Repository
public class NotificationBulkRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public NotificationBulkRepository(
		NamedParameterJdbcTemplate jdbcTemplate,
		ObjectMapper objectMapper
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public int insertForClassroomMembers(
		Long classroomId,
		NotificationType type,
		String title,
		String body,
		Map<String, Object> link,
		Instant createdAt
	) {
		return jdbcTemplate.update("""
			INSERT INTO notifications (
			    user_id, type, title, body, link_json, created_at
			)
			SELECT member.user_id, :type, :title, :body, :linkJson, :createdAt
			FROM classroom_members member
			WHERE member.classroom_id = :classroomId
			""", parameters(type, title, body, link, createdAt)
			.addValue("classroomId", classroomId));
	}

	public int insertForUser(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Map<String, Object> link,
		Instant createdAt
	) {
		return jdbcTemplate.update("""
			INSERT INTO notifications (
			    user_id, type, title, body, link_json, created_at
			)
			VALUES (
			    :userId, :type, :title, :body, :linkJson, :createdAt
			)
			""", parameters(type, title, body, link, createdAt)
			.addValue("userId", userId));
	}

	public int deleteExpired(Instant cutoff, int limit) {
		return jdbcTemplate.update("""
			DELETE FROM notifications
			WHERE created_at < :cutoff
			ORDER BY created_at, id
			LIMIT :limit
			""", new MapSqlParameterSource()
			.addValue("cutoff", cutoff)
			.addValue("limit", limit));
	}

	private MapSqlParameterSource parameters(
		NotificationType type,
		String title,
		String body,
		Map<String, Object> link,
		Instant createdAt
	) {
		return new MapSqlParameterSource()
			.addValue("type", type.name())
			.addValue("title", title)
			.addValue("body", body)
			.addValue("linkJson", objectMapper.writeValueAsString(link))
			.addValue("createdAt", createdAt);
	}
}
