package io.edupilot.schedule;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.schedule.dto.CreatePersonalScheduleRequest;
import io.edupilot.schedule.dto.PersonalScheduleResponse;
import io.edupilot.schedule.dto.UpdatePersonalScheduleRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@Service
public class PersonalScheduleService {

	private static final int TITLE_MAX_LENGTH = 200;

	private final UserScheduleRepository scheduleRepository;
	private final UserRepository userRepository;

	public PersonalScheduleService(
		UserScheduleRepository scheduleRepository,
		UserRepository userRepository
	) {
		this.scheduleRepository = scheduleRepository;
		this.userRepository = userRepository;
	}

	@Transactional
	public PersonalScheduleResponse create(
		Long userId,
		CreatePersonalScheduleRequest request
	) {
		String title = validateTitle(request.title());
		validateRange(request.startsAt(), request.endsAt());
		if (request.hasTime() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		UserSchedule schedule = scheduleRepository.saveAndFlush(UserSchedule.create(
			user,
			title,
			request.startsAt(),
			request.endsAt(),
			request.hasTime()
		));
		return PersonalScheduleResponse.from(schedule);
	}

	@Transactional
	public PersonalScheduleResponse update(
		Long userId,
		Long scheduleId,
		UpdatePersonalScheduleRequest request
	) {
		if (!request.hasAnyChange()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		UserSchedule schedule = ownedSchedule(userId, scheduleId);
		String title = request.title() == null
			? null
			: validateTitle(request.title());
		Instant startsAt = request.startsAt() == null
			? schedule.getStartsAt()
			: request.startsAt();
		Instant endsAt = request.endsAt() == null
			? schedule.getEndsAt()
			: request.endsAt();
		validateRange(startsAt, endsAt);
		schedule.update(title, request.startsAt(), request.endsAt(), request.hasTime());
		scheduleRepository.flush();
		return PersonalScheduleResponse.from(schedule);
	}

	@Transactional
	public void delete(Long userId, Long scheduleId) {
		UserSchedule schedule = ownedSchedule(userId, scheduleId);
		scheduleRepository.delete(schedule);
	}

	private UserSchedule ownedSchedule(Long userId, Long scheduleId) {
		return scheduleRepository.findByIdAndUser_Id(scheduleId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND));
	}

	private String validateTitle(String title) {
		if (title == null || title.isBlank() || title.length() > TITLE_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		return title.trim();
	}

	private void validateRange(Instant startsAt, Instant endsAt) {
		if (startsAt == null || endsAt == null || endsAt.isBefore(startsAt)) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
}
