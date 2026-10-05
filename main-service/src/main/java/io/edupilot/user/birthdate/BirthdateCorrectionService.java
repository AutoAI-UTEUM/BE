package io.edupilot.user.birthdate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.guardian.BirthdatePolicy;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;
import io.edupilot.user.UserStatus;

@Service
public class BirthdateCorrectionService {
	private final UserRepository users;
	private final BirthdateCorrectionRepository requests;
	private final Clock clock;

	public BirthdateCorrectionService(UserRepository users, BirthdateCorrectionRepository requests, Clock clock) {
		this.users = users;
		this.requests = requests;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public BirthdateCorrectionDtos.Request submit(Long userId, LocalDate requestedDate) {
		BirthdatePolicy.validate(requestedDate, clock);
		// First read is a current User lock in an independent transaction. Withdrawal uses the same lock order.
		User user = users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
		assertActive(user);
		if (Objects.equals(user.getDateOfBirth(), requestedDate)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		BirthdateCorrectionRequest existing = requests.findByUser_Id(userId).orElse(null);
		if (existing != null) {
			if (existing.getState() == BirthdateCorrectionRequest.State.PENDING
				&& requestedDate.equals(existing.getRequestedDateOfBirth())) return BirthdateCorrectionDtos.Request.from(existing);
			throw new BusinessException(ErrorCode.BIRTHDATE_CORRECTION_PENDING);
		}
		return BirthdateCorrectionDtos.Request.from(requests.saveAndFlush(
			BirthdateCorrectionRequest.pending(user, requestedDate, clock.instant().truncatedTo(ChronoUnit.MICROS))));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public BirthdateCorrectionDtos.Request mine(Long userId) {
		assertActive(users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND)));
		return requests.findByUser_Id(userId).map(BirthdateCorrectionDtos.Request::from).orElse(null);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public BirthdateCorrectionDtos.ListResponse pending(Long actorUserId, int page, int size) {
		assertAdmin(actorUserId);
		if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		var rows = requests.findByState(BirthdateCorrectionRequest.State.PENDING,
			PageRequest.of(page, size, Sort.by("requestedAt", "id").ascending())).map(BirthdateCorrectionDtos.Request::from);
		return new BirthdateCorrectionDtos.ListResponse(rows.getContent(), rows.getNumber(), rows.getSize(), rows.getTotalElements(), rows.getTotalPages());
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public BirthdateCorrectionDtos.Request detail(Long actorUserId, Long requestId) {
		assertAdmin(actorUserId);
		return requests.findById(requestId).map(BirthdateCorrectionDtos.Request::from)
			.orElseThrow(() -> new BusinessException(ErrorCode.BIRTHDATE_CORRECTION_NOT_FOUND));
	}

	private void assertAdmin(Long actorUserId) {
		var state = users.findAccessStateById(actorUserId).orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
		if (state.status() == UserStatus.SUSPENDED) throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		if (state.status() != UserStatus.ACTIVE || state.role() != UserRole.ADMIN) throw new BusinessException(ErrorCode.ACCESS_DENIED);
	}

	private void assertActive(User user) {
		if (user.getStatus() == UserStatus.SUSPENDED) throw new BusinessException(ErrorCode.ACCOUNT_SUSPENDED);
		if (!user.isActive()) throw new BusinessException(ErrorCode.USER_INACTIVE);
	}
}
