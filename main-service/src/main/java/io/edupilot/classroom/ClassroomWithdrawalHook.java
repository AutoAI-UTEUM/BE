package io.edupilot.classroom;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.user.UserWithdrawalHook;

@Component
public class ClassroomWithdrawalHook implements UserWithdrawalHook {
	private final ClassroomRepository classrooms;
	private final Clock clock;

	public ClassroomWithdrawalHook(ClassroomRepository classrooms, Clock clock) {
		this.classrooms = classrooms;
		this.clock = clock;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onWithdraw(Long userId) {
		classrooms.completeAllActiveByInstructorId(userId, clock.instant());
	}
}
