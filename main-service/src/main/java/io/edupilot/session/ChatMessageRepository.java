package io.edupilot.session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	Optional<ChatMessage> findBySession_IdAndRequestId(
		Long sessionId,
		String requestId
	);

	Optional<ChatMessage> findByIdAndSession_Id(Long id, Long sessionId);

	List<ChatMessage> findBySession_IdOrderByCreatedAtDescIdDesc(
		Long sessionId,
		Pageable pageable
	);

	@Query("""
		select message
		from ChatMessage message
		where message.session.id = :sessionId
		  and message.status <> io.edupilot.session.ChatMessageStatus.FAILED
		order by message.createdAt desc, message.id desc
		""")
	List<ChatMessage> findRecentContextMessages(
		@Param("sessionId") Long sessionId,
		Pageable pageable
	);

	@Query("""
		select count(message)
		from ChatMessage message
		where message.session.id = :sessionId
		  and message.status = io.edupilot.session.ChatMessageStatus.COMPLETED
		  and message.senderType = io.edupilot.session.SenderType.USER
		  and (
		    (:lastMessageId is not null and message.id > :lastMessageId)
		    or (:lastMessageId is null and :resetAt is null)
		    or (
		      :lastMessageId is null
		      and :resetAt is not null
		      and message.createdAt > :resetAt
		    )
		  )
		""")
	long countCompletedUserMessagesAfterBoundary(
		@Param("sessionId") Long sessionId,
		@Param("lastMessageId") Long lastMessageId,
		@Param("resetAt") Instant resetAt
	);

	@Query("""
		select message
		from ChatMessage message
		where message.session.id = :sessionId
		  and message.status = io.edupilot.session.ChatMessageStatus.COMPLETED
		  and (
		    (:lastMessageId is not null and message.id > :lastMessageId)
		    or (:lastMessageId is null and :resetAt is null)
		    or (
		      :lastMessageId is null
		      and :resetAt is not null
		      and message.createdAt > :resetAt
		    )
		  )
		order by message.createdAt, message.id
		""")
	List<ChatMessage> findCompletedMessagesAfterBoundary(
		@Param("sessionId") Long sessionId,
		@Param("lastMessageId") Long lastMessageId,
		@Param("resetAt") Instant resetAt,
		Pageable pageable
	);

	@Query("""
		select message
		from ChatMessage message
		where message.session.id = :sessionId
		  and (
		    message.createdAt < :createdAt
		    or (message.createdAt = :createdAt and message.id < :messageId)
		  )
		order by message.createdAt desc, message.id desc
		""")
	List<ChatMessage> findOlderThan(
		@Param("sessionId") Long sessionId,
		@Param("createdAt") Instant createdAt,
		@Param("messageId") Long messageId,
		Pageable pageable
	);
}
