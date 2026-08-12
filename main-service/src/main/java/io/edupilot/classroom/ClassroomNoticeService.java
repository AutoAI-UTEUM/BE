package io.edupilot.classroom;

import java.time.Clock;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.dto.ClassroomNoticeListResponse;
import io.edupilot.classroom.dto.ClassroomNoticeResponse;
import io.edupilot.classroom.dto.CreateClassroomNoticeRequest;
import io.edupilot.classroom.dto.UpdateClassroomNoticeRequest;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.notification.NotificationTriggerService;
import io.edupilot.user.UserRole;

@Service
public class ClassroomNoticeService {

	private final ClassroomService classroomService;
	private final ClassroomNoticeRepository noticeRepository;
	private final NotificationTriggerService notificationTriggerService;
	private final Clock clock;

	public ClassroomNoticeService(
		ClassroomService classroomService,
		ClassroomNoticeRepository noticeRepository,
		NotificationTriggerService notificationTriggerService,
		Clock clock
	) {
		this.classroomService = classroomService;
		this.noticeRepository = noticeRepository;
		this.notificationTriggerService = notificationTriggerService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public ClassroomNoticeListResponse list(
		Long userId,
		UserRole role,
		Long classroomId,
		int page,
		int size
	) {
		Classroom classroom = classroomService.requireVisible(
			userId,
			role,
			classroomId
		);
		var now = clock.instant();
		PageRequest pageable = PageRequest.of(
			page,
			size,
			Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"))
		);
		var notices = classroom.getInstructorId().equals(userId)
			? noticeRepository.findByClassroom_Id(classroomId, pageable)
			: noticeRepository.findPublishedByClassroomId(
				classroomId,
				now,
				pageable
			);
		return ClassroomNoticeListResponse.from(notices, now);
	}

	@Transactional
	public ClassroomNoticeResponse create(
		Long userId,
		UserRole role,
		Long classroomId,
		CreateClassroomNoticeRequest request
	) {
		Classroom classroom = writableOwner(userId, role, classroomId);
		validateWeekNumber(classroom, request.weekNumber());
		var now = clock.instant();
		ClassroomNotice notice = noticeRepository.saveAndFlush(
			ClassroomNotice.create(
				classroom,
				normalizedRequired(request.title(), 200),
				normalizedContent(request.content()),
				request.weekNumber(),
				request.publishAt(),
				now
			)
		);
		notificationTriggerService.noticePublished(notice, now);
		return ClassroomNoticeResponse.from(notice, now);
	}

	@Transactional
	public ClassroomNoticeResponse update(
		Long userId,
		UserRole role,
		Long classroomId,
		Long noticeId,
		UpdateClassroomNoticeRequest request
	) {
		Classroom classroom = writableOwner(userId, role, classroomId);
		if (!request.hasAnyField()
			|| request.isTitlePresent() && request.getTitle() == null
			|| request.isContentPresent() && request.getContent() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if (request.isWeekNumberPresent()) {
			validateWeekNumber(classroom, request.getWeekNumber());
		}
		ClassroomNotice notice = noticeRepository.findForUpdate(
			classroomId,
			noticeId
		).orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		notice.update(
			request.isTitlePresent()
				? normalizedRequired(request.getTitle(), 200)
				: null,
			request.isContentPresent()
				? normalizedContent(request.getContent())
				: null,
			request.isWeekNumberPresent(),
			request.getWeekNumber(),
			request.isPublishAtPresent(),
			request.getPublishAt()
		);
		var now = clock.instant();
		notificationTriggerService.noticePublished(notice, now);
		noticeRepository.flush();
		return ClassroomNoticeResponse.from(notice, now);
	}

	@Transactional
	public void delete(
		Long userId,
		UserRole role,
		Long classroomId,
		Long noticeId
	) {
		writableOwner(userId, role, classroomId);
		ClassroomNotice notice = noticeRepository.findForUpdate(
			classroomId,
			noticeId
		).orElseThrow(() -> new BusinessException(ErrorCode.CLASSROOM_NOT_FOUND));
		noticeRepository.delete(notice);
	}

	private Classroom writableOwner(
		Long userId,
		UserRole role,
		Long classroomId
	) {
		Classroom classroom = classroomService.requireOwnerForUpdate(
			userId,
			role,
			classroomId
		);
		classroomService.assertWritable(classroom);
		return classroom;
	}

	private String normalizedRequired(String value, int maxLength) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty() || normalized.length() > maxLength) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}

	private void validateWeekNumber(Classroom classroom, Integer weekNumber) {
		if (weekNumber != null
			&& (weekNumber < 1 || weekNumber > classroom.getWeekCount())) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private String normalizedContent(String value) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return normalized;
	}
}
