package io.edupilot.usernote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
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
import io.edupilot.material.MaterialAccessService;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.usernote.dto.CreateUserNoteRequest;

@ExtendWith(MockitoExtension.class)
class UserNoteServiceTest {

	@Mock private UserNoteRepository notes;
	@Mock private UserRepository users;
	@Mock private MaterialAccessService materialAccessService;
	@Mock private Clock clock;
	private UserNoteService service;

	@BeforeEach
	void setUp() {
		service = new UserNoteService(notes, users, materialAccessService, clock);
	}

	@Test
	void rejectsTwoThousandAndFirstActiveNote() {
		User owner = User.create("owner@test.com", "hash", "소유자");
		when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
		when(notes.countByUser_IdAndDeletedAtIsNull(1L)).thenReturn(2_000L);
		assertThatThrownBy(() -> service.create(1L,
			new CreateUserNoteRequest(null, null, "제목", "내용", null), null))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
				.isEqualTo(ErrorCode.NOTE_LIMIT_EXCEEDED));
	}

	@Test
	void acceptsOneMegabyteAndRejectsLargerContent() {
		User owner = User.create("owner@test.com", "hash", "소유자");
		ReflectionTestUtils.setField(owner, "id", 1L);
		when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
		when(clock.instant()).thenReturn(Instant.parse("2026-09-25T00:00:00Z"));
		when(notes.saveAndFlush(any(UserNote.class))).thenAnswer(invocation -> {
			UserNote note = invocation.getArgument(0);
			ReflectionTestUtils.setField(note, "id", 10L);
			return note;
		});
		var created = service.create(1L, new CreateUserNoteRequest(
			null, null, "제목", "a".repeat(1024 * 1024), null), null);
		assertThat(created.created()).isTrue();
		assertThatThrownBy(() -> service.create(1L, new CreateUserNoteRequest(
			null, null, "제목", "a".repeat(1024 * 1024 + 1), null), null))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException) exception).errorCode())
				.isEqualTo(ErrorCode.NOTE_TOO_LARGE));
	}
}
