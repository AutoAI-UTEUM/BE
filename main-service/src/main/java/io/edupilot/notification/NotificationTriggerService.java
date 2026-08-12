package io.edupilot.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.ClassroomJoinRequest;
import io.edupilot.classroom.ClassroomJoinRequestStatus;
import io.edupilot.classroom.ClassroomNotice;
import io.edupilot.classroom.ClassroomNoticeRepository;

@Service
public class NotificationTriggerService {

	private final NotificationBulkRepository bulkRepository;
	private final ClassroomNoticeRepository noticeRepository;
	private final Clock clock;

	public NotificationTriggerService(
		NotificationBulkRepository bulkRepository,
		ClassroomNoticeRepository noticeRepository,
		Clock clock
	) {
		this.bulkRepository = bulkRepository;
		this.noticeRepository = noticeRepository;
		this.clock = clock;
	}

	public void materialUploaded(
		Long classroomId,
		Long materialId,
		String materialTitle
	) {
		bulkRepository.insertForClassroomMembers(
			classroomId,
			NotificationType.MATERIAL_UPLOADED,
			"새 학습 자료가 등록되었습니다",
			materialTitle,
			link("classroomId", classroomId, "materialId", materialId),
			clock.instant()
		);
	}

	public void noticePublished(ClassroomNotice notice, Instant now) {
		if (notice.isNotificationSent() || !notice.isPublished(now)) {
			return;
		}
		bulkRepository.insertForClassroomMembers(
			notice.getClassroom().getId(),
			NotificationType.NOTICE_PUBLISHED,
			notice.getTitle(),
			notice.getContent(),
			link(
				"classroomId", notice.getClassroom().getId(),
				"noticeId", notice.getId()
			),
			now
		);
		notice.markNotificationSent(now);
	}

	public void joinRequestReceived(ClassroomJoinRequest request) {
		bulkRepository.insertForUser(
			request.getClassroom().getInstructorId(),
			NotificationType.JOIN_REQUEST_RECEIVED,
			"새 강의실 입장 요청",
			request.getUser().getName(),
			link(
				"classroomId", request.getClassroom().getId(),
				"joinRequestId", request.getId()
			),
			clock.instant()
		);
	}

	public void joinRequestProcessed(ClassroomJoinRequest request) {
		ClassroomJoinRequestStatus status = request.getStatus();
		String title = status == ClassroomJoinRequestStatus.APPROVED
			? "강의실 입장 요청이 승인되었습니다"
			: "강의실 입장 요청이 거절되었습니다";
		bulkRepository.insertForUser(
			request.getUser().getId(),
			NotificationType.JOIN_REQUEST_PROCESSED,
			title,
			request.getClassroom().getName(),
			link(
				"classroomId", request.getClassroom().getId(),
				"joinRequestId", request.getId()
			),
			clock.instant()
		);
	}

	@Transactional
	public int publishDueNotices(Instant now, int limit) {
		var notices = noticeRepository.findNotificationCandidates(
			now,
			PageRequest.of(0, limit)
		);
		for (ClassroomNotice notice : notices) {
			noticePublished(notice, now);
		}
		noticeRepository.flush();
		return notices.size();
	}

	@Transactional
	public int deleteExpired(Instant cutoff, int limit) {
		return bulkRepository.deleteExpired(cutoff, limit);
	}

	private Map<String, Object> link(
		String firstKey,
		Long firstValue,
		String secondKey,
		Long secondValue
	) {
		Map<String, Object> link = new LinkedHashMap<>();
		link.put(firstKey, firstValue);
		link.put(secondKey, secondValue);
		return link;
	}
}
