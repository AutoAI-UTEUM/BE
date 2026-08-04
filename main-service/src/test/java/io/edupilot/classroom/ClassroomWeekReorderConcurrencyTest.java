package io.edupilot.classroom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import io.edupilot.classroom.dto.CreateClassroomRequest;
import io.edupilot.classroom.dto.CreateClassroomWeekRequest;
import io.edupilot.classroom.dto.ReorderClassroomWeeksRequest;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:week-reorder;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/week-reorder"
	}
)
@ActiveProfiles("jpa-context")
class ClassroomWeekReorderConcurrencyTest {

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private ClassroomService classroomService;
	@Autowired
	private ClassroomWeekService weekService;
	@Autowired
	private ClassroomWeekRepository weekRepository;

	@Test
	void concurrentReordersAlwaysCommitOneCompletePermutation() throws Exception {
		User instructor = userRepository.save(User.create(
			"reorder@example.com",
			"hash",
			"Instructor",
			UserRole.INSTRUCTOR
		));
		var classroom = classroomService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			new CreateClassroomRequest(
				"Concurrent reorder",
				LocalDate.of(2026, 8, 1),
				LocalDate.of(2026, 8, 14),
				ClassroomColor.BLUE,
				null
			)
		);
		var first = weekService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			new CreateClassroomWeekRequest(1, "Week 1", null)
		);
		var second = weekService.create(
			instructor.getId(),
			UserRole.INSTRUCTOR,
			classroom.classroomId(),
			new CreateClassroomWeekRequest(2, "Week 2", null)
		);
		List<Long> forward = List.of(first.weekId(), second.weekId());
		List<Long> reverse = List.of(second.weekId(), first.weekId());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var forwardResult = executor.submit(() -> reorderAfterStart(
				ready,
				start,
				instructor.getId(),
				classroom.classroomId(),
				forward
			));
			var reverseResult = executor.submit(() -> reorderAfterStart(
				ready,
				start,
				instructor.getId(),
				classroom.classroomId(),
				reverse
			));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			forwardResult.get(10, TimeUnit.SECONDS);
			reverseResult.get(10, TimeUnit.SECONDS);
		} finally {
			executor.shutdownNow();
		}

		var stored = weekRepository.findByClassroom_IdOrderByDisplayOrderAscIdAsc(
			classroom.classroomId()
		);
		assertThat(stored).extracting(ClassroomWeek::getDisplayOrder)
			.containsExactly(1, 2);
		List<Long> storedIds = stored.stream().map(ClassroomWeek::getId).toList();
		assertThat(storedIds.equals(forward) || storedIds.equals(reverse)).isTrue();
		assertThat(weekRepository.findById(first.weekId()).orElseThrow().getWeekNumber())
			.isEqualTo(1);
		assertThat(weekRepository.findById(second.weekId()).orElseThrow().getWeekNumber())
			.isEqualTo(2);
	}

	private void reorderAfterStart(
		CountDownLatch ready,
		CountDownLatch start,
		Long instructorId,
		Long classroomId,
		List<Long> orderedWeekIds
	) {
		ready.countDown();
		try {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Concurrent reorder did not start");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Concurrent reorder was interrupted", exception);
		}
		weekService.reorder(
			instructorId,
			UserRole.INSTRUCTOR,
			classroomId,
			new ReorderClassroomWeeksRequest(orderedWeekIds)
		);
	}
}
