package io.edupilot.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.material.storage.FileStorage;
import io.edupilot.session.SessionPageRecordRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:user-schedule-jpa;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/user-schedule-jpa"
	}
)
@ActiveProfiles("jpa-context")
class UserScheduleJpaTest {

	private static final Instant DAY_START = Instant.parse("2026-08-03T00:00:00Z");
	private static final Instant DAY_END = Instant.parse("2026-08-04T00:00:00Z");

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UserScheduleRepository scheduleRepository;
	@MockitoBean
	private SessionPageRecordRepository pageRecordRepository;
	@MockitoBean
	private FileStorage fileStorage;

	@Test
	void rangeQueryReturnsOnlyOwnedSchedulesAndRoundTripsAllDayValues() {
		User owner = userRepository.save(User.create(
			"owner@example.com", "hash", "Owner"
		));
		User other = userRepository.save(User.create(
			"other@example.com", "hash", "Other"
		));
		UserSchedule owned = scheduleRepository.saveAndFlush(UserSchedule.create(
			owner, "All day", DAY_START, DAY_START, false
		));
		scheduleRepository.saveAndFlush(UserSchedule.create(
			other, "Other user", DAY_START, DAY_START.plusSeconds(60), true
		));

		assertThat(scheduleRepository.findVisibleInRange(
			owner.getId(), DAY_START, DAY_END
		)).singleElement().satisfies(schedule -> {
			assertThat(schedule.getId()).isEqualTo(owned.getId());
			assertThat(schedule.getStartsAt()).isEqualTo(DAY_START);
			assertThat(schedule.getEndsAt()).isEqualTo(DAY_START);
			assertThat(schedule.hasTime()).isFalse();
		});
		assertThat(scheduleRepository.findByIdAndUser_Id(
			owned.getId(), other.getId()
		)).isEmpty();
	}
}
