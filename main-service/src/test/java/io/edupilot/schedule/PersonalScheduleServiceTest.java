package io.edupilot.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.schedule.dto.CreatePersonalScheduleRequest;
import io.edupilot.schedule.dto.UpdatePersonalScheduleRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class PersonalScheduleServiceTest {

	private static final Instant START = Instant.parse("2026-08-03T00:00:00Z");

	@Mock
	private UserScheduleRepository scheduleRepository;
	@Mock
	private UserRepository userRepository;

	private PersonalScheduleService service;
	private User user;

	@BeforeEach
	void setUp() {
		service = new PersonalScheduleService(scheduleRepository, userRepository);
		user = User.create("learner@example.com", "hash", "Learner");
		ReflectionTestUtils.setField(user, "id", 1L);
	}

	@Test
	void createsAllDayScheduleAndAllowsEqualStartAndEnd() {
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		doAnswer(invocation -> {
			UserSchedule schedule = invocation.getArgument(0);
			ReflectionTestUtils.setField(schedule, "id", 10L);
			return schedule;
		}).when(scheduleRepository).saveAndFlush(any(UserSchedule.class));

		var response = service.create(1L, new CreatePersonalScheduleRequest(
			" All-day checkpoint ", START, START, false
		));

		assertThat(response.scheduleId()).isEqualTo("10");
		assertThat(response.kind()).isEqualTo(ScheduleType.PERSONAL);
		assertThat(response.title()).isEqualTo("All-day checkpoint");
		assertThat(response.startsAt()).isEqualTo(START);
		assertThat(response.endsAt()).isEqualTo(START);
		assertThat(response.hasTime()).isFalse();
	}

	@Test
	void rejectsEndBeforeStart() {
		assertThatThrownBy(() -> service.create(
			1L,
			new CreatePersonalScheduleRequest(
				"Invalid", START, START.minusSeconds(1), true
			)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
		verify(userRepository, never()).findById(any());
	}

	@Test
	void validatesMergedRangeWhenPartiallyUpdating() {
		UserSchedule schedule = schedule(10L, START, START.plusSeconds(3600));
		when(scheduleRepository.findByIdAndUser_Id(10L, 1L))
			.thenReturn(Optional.of(schedule));

		assertThatThrownBy(() -> service.update(
			1L,
			10L,
			new UpdatePersonalScheduleRequest(
				null, START.plusSeconds(7200), null, null
			)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED)
		);
		verify(scheduleRepository, never()).flush();
	}

	@Test
	void hidesMissingOrOtherUsersScheduleForUpdateAndDelete() {
		when(scheduleRepository.findByIdAndUser_Id(20L, 1L))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(
			1L,
			20L,
			new UpdatePersonalScheduleRequest("Hidden", null, null, null)
		)).isInstanceOfSatisfying(BusinessException.class, exception ->
			assertThat(exception.errorCode()).isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND)
		);
		assertThatThrownBy(() -> service.delete(1L, 20L))
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(ErrorCode.SCHEDULE_NOT_FOUND)
			);
	}

	private UserSchedule schedule(Long id, Instant startsAt, Instant endsAt) {
		UserSchedule schedule = UserSchedule.create(
			user, "Schedule", startsAt, endsAt, true
		);
		ReflectionTestUtils.setField(schedule, "id", id);
		return schedule;
	}
}
