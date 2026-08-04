package io.edupilot.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomNoticeRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomService;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.schedule.dto.ScheduleItemResponse;
import io.edupilot.schedule.dto.ScheduleListResponse;
import io.edupilot.user.UserRole;

@Service
public class ScheduleService {

	private static final Comparator<ScheduleItemResponse> SCHEDULE_ORDER =
		Comparator.comparing(ScheduleItemResponse::dateTime)
			.thenComparing(ScheduleItemResponse::scheduleId);

	private final ClassroomService classroomService;
	private final ClassroomRepository classroomRepository;
	private final ClassroomWeekRepository weekRepository;
	private final ClassroomNoticeRepository noticeRepository;
	private final UserScheduleRepository userScheduleRepository;

	public ScheduleService(
		ClassroomService classroomService,
		ClassroomRepository classroomRepository,
		ClassroomWeekRepository weekRepository,
		ClassroomNoticeRepository noticeRepository,
		UserScheduleRepository userScheduleRepository
	) {
		this.classroomService = classroomService;
		this.classroomRepository = classroomRepository;
		this.weekRepository = weekRepository;
		this.noticeRepository = noticeRepository;
		this.userScheduleRepository = userScheduleRepository;
	}

	@Transactional(readOnly = true)
	public ScheduleListResponse list(
		Long userId,
		UserRole role,
		LocalDate from,
		LocalDate to,
		Long classroomId
	) {
		validate(role, from, to);
		List<Classroom> classrooms = classroomId == null
			? classroomRepository.findAllVisibleByUserId(userId)
			: List.of(classroomService.requireVisible(userId, role, classroomId));
		List<Long> classroomIds = classrooms.stream()
			.map(Classroom::getId)
			.toList();
		Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant toExclusive = to.plusDays(1)
			.atStartOfDay(ZoneOffset.UTC)
			.toInstant();
		List<ScheduleItemResponse> derivedItems = classrooms.isEmpty()
			? List.of()
			: java.util.stream.Stream.concat(
				weekRepository.findScheduleWeeks(
					classroomIds,
					fromInstant,
					toExclusive
				).stream().map(ScheduleItemResponse::from),
				noticeRepository.findScheduleNotices(
					classroomIds,
					fromInstant,
					toExclusive
				).stream().map(ScheduleItemResponse::from)
			).toList();
		List<ScheduleItemResponse> personalItems = classroomId == null
			? userScheduleRepository.findVisibleInRange(
				userId,
				fromInstant,
				toExclusive
			).stream().map(ScheduleItemResponse::from).toList()
			: List.of();
		List<ScheduleItemResponse> items = java.util.stream.Stream.concat(
			derivedItems.stream(),
			personalItems.stream()
		).sorted(SCHEDULE_ORDER).toList();
		return new ScheduleListResponse(items);
	}

	private void validate(UserRole role, LocalDate from, LocalDate to) {
		if (role != UserRole.LEARNER && role != UserRole.INSTRUCTOR) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		if (from == null
			|| to == null
			|| to.isBefore(from)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
}
