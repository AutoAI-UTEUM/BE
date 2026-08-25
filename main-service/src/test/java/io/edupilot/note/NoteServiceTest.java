package io.edupilot.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialStatus;
import io.edupilot.note.dto.CreateNoteRequest;
import io.edupilot.note.dto.NoteResponse;
import io.edupilot.note.dto.UpdateNoteRequest;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

	@Mock
	private NoteRepository noteRepository;

	@Mock
	private LearningSessionRepository sessionRepository;

	@Mock
	private MaterialAccessService materialAccessService;

	@Mock
	private ChatMessageRepository messageRepository;

	@Mock
	private UserRepository userRepository;

	private NoteService noteService;
	private User user;
	private LearningMaterial material;
	private LearningSession session;

	@BeforeEach
	void setUp() {
		noteService = new NoteService(
			noteRepository,
			sessionRepository,
			materialAccessService,
			messageRepository,
			userRepository
		);
		user = User.create("user@example.com", "hash", "학습자");
		ReflectionTestUtils.setField(user, "id", 1L);
		material = LearningMaterial.create(user, "자료", "materials/test.pdf");
		ReflectionTestUtils.setField(material, "id", 10L);
		material.markReady(5);
		session = LearningSession.create(user, material);
		ReflectionTestUtils.setField(session, "id", 100L);
	}

	@Test
	void createsNotesWithAndWithoutOptionalReferences() {
		ChatMessage message = ChatMessage.user(session, "질문", "request-1");
		ReflectionTestUtils.setField(message, "id", 501L);
		stubSessionAndAccessibleMaterial();
		when(messageRepository.findByIdAndSession_Id(501L, 100L))
			.thenReturn(Optional.of(message));
		when(userRepository.getReferenceById(1L)).thenReturn(user);
		when(noteRepository.saveAndFlush(any(Note.class)))
			.thenAnswer(invocation -> persisted(invocation.getArgument(0), 1000L));

		var referenced = noteService.create(
			1L,
			100L,
			new CreateNoteRequest("핵심 개념", 3, 501L)
		);
		var plain = noteService.create(
			1L,
			100L,
			new CreateNoteRequest("참조 없는 노트", null, null)
		);

		assertThat(referenced.noteId()).isEqualTo(1000L);
		assertThat(referenced.sessionId()).isEqualTo(100L);
		assertThat(referenced.materialId()).isEqualTo(10L);
		assertThat(referenced.pageNumber()).isEqualTo(3);
		assertThat(referenced.sourceMessageId()).isEqualTo(501L);
		assertThat(plain.pageNumber()).isNull();
		assertThat(plain.sourceMessageId()).isNull();
	}

	@Test
	void rejectsBlankOversizedAndOutOfRangeContentOrPage() {
		assertError(
			() -> noteService.create(
				1L,
				100L,
				new CreateNoteRequest(" ", null, null)
			),
			ErrorCode.VALIDATION_FAILED
		);
		assertError(
			() -> noteService.create(
				1L,
				100L,
				new CreateNoteRequest("a".repeat(10001), null, null)
			),
			ErrorCode.VALIDATION_FAILED
		);

		stubSessionAndAccessibleMaterial();
		assertError(
			() -> noteService.create(
				1L,
				100L,
				new CreateNoteRequest("내용", 6, null)
			),
			ErrorCode.PAGE_OUT_OF_RANGE
		);
	}

	@Test
	void rejectsOtherUsersSessionAndMessageFromAnotherSession() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.empty());
		assertError(
			() -> noteService.create(
				1L,
				100L,
				new CreateNoteRequest("내용", null, null)
			),
			ErrorCode.SESSION_NOT_FOUND
		);

		stubSessionAndAccessibleMaterial();
		when(messageRepository.findByIdAndSession_Id(501L, 100L))
			.thenReturn(Optional.empty());
		assertError(
			() -> noteService.create(
				1L,
				100L,
				new CreateNoteRequest("내용", null, 501L)
			),
			ErrorCode.VALIDATION_FAILED
		);
	}

	@Test
	void listsBothRoutesByMaterialWithStablePaginationAndLatestSort() {
		Note note = persisted(
			Note.create(user, material, session, 3, null, "내용"),
			1000L
		);
		stubSessionAndAccessibleMaterial();
		when(noteRepository.findByUser_IdAndMaterial_IdAndMaterial_Status(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(10L),
			org.mockito.ArgumentMatchers.eq(MaterialStatus.ACTIVE),
			any(Pageable.class)
		)).thenAnswer(invocation -> new PageImpl<>(
			List.of(note),
			invocation.getArgument(3),
			1
		));

		var materialRoute = noteService.listByMaterial(1L, 10L, 0, 50);
		var sessionRoute = noteService.listBySession(1L, 100L, 0, 50);

		assertThat(sessionRoute).isEqualTo(materialRoute);
		assertThat(materialRoute.page()).isZero();
		assertThat(materialRoute.size()).isEqualTo(50);
		assertThat(materialRoute.totalElements()).isEqualTo(1);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(noteRepository, org.mockito.Mockito.times(2))
			.findByUser_IdAndMaterial_IdAndMaterial_Status(
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(10L),
				org.mockito.ArgumentMatchers.eq(MaterialStatus.ACTIVE),
				pageable.capture()
			);
		assertThat(pageable.getValue().getSort().getOrderFor("createdAt").isDescending())
			.isTrue();
		assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending())
			.isTrue();
	}

	@Test
	void excludesDeletedMaterialsFromLists() {
		when(materialAccessService.requireAccessible(1L, 10L))
			.thenThrow(new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));

		assertError(
			() -> noteService.listByMaterial(1L, 10L, 0, 50),
			ErrorCode.MATERIAL_NOT_FOUND
		);
	}

	@Test
	void classroomMemberCreatesAndListsOwnNotesAcrossAllRoutes() {
		User instructor = User.create("instructor@example.com", "hash", "강사");
		ReflectionTestUtils.setField(instructor, "id", 2L);
		LearningMaterial classroomMaterial = LearningMaterial.create(
			instructor,
			"강의실 자료",
			"materials/classroom.pdf"
		);
		ReflectionTestUtils.setField(classroomMaterial, "id", 20L);
		classroomMaterial.markReady(5);
		LearningSession classroomSession = LearningSession.create(
			user,
			classroomMaterial
		);
		ReflectionTestUtils.setField(classroomSession, "id", 200L);
		Note note = persisted(
			Note.create(
				user,
				classroomMaterial,
				classroomSession,
				2,
				null,
				"학생 노트"
			),
			2000L
		);

		when(sessionRepository.findByIdAndUser_Id(200L, 1L))
			.thenReturn(Optional.of(classroomSession));
		when(materialAccessService.requireAccessible(1L, 20L))
			.thenReturn(classroomMaterial);
		when(userRepository.getReferenceById(1L)).thenReturn(user);
		when(noteRepository.saveAndFlush(any(Note.class))).thenReturn(note);
		when(noteRepository.findByUser_IdAndMaterial_IdAndMaterial_Status(
			org.mockito.ArgumentMatchers.eq(1L),
			org.mockito.ArgumentMatchers.eq(20L),
			org.mockito.ArgumentMatchers.eq(MaterialStatus.ACTIVE),
			any(Pageable.class)
		)).thenAnswer(invocation -> new PageImpl<>(
			List.of(note),
			invocation.getArgument(3),
			1
		));

		var created = noteService.create(
			1L,
			200L,
			new CreateNoteRequest("학생 노트", 2, null)
		);
		var sessionNotes = noteService.listBySession(1L, 200L, 0, 50);
		var materialNotes = noteService.listByMaterial(1L, 20L, 0, 50);

		assertThat(created.materialId()).isEqualTo(20L);
		assertThat(sessionNotes.items()).containsExactly(NoteResponse.from(note));
		assertThat(materialNotes.items()).containsExactly(NoteResponse.from(note));
		verify(noteRepository, org.mockito.Mockito.times(2))
			.findByUser_IdAndMaterial_IdAndMaterial_Status(
				org.mockito.ArgumentMatchers.eq(1L),
				org.mockito.ArgumentMatchers.eq(20L),
				org.mockito.ArgumentMatchers.eq(MaterialStatus.ACTIVE),
				any(Pageable.class)
			);
	}

	@Test
	void inaccessibleClassroomMaterialIsHiddenAcrossAllRoutes() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(materialAccessService.requireAccessible(1L, 10L))
			.thenThrow(new BusinessException(ErrorCode.MATERIAL_NOT_FOUND));

		assertError(
			() -> noteService.create(
				1L,
				100L,
				new CreateNoteRequest("내용", null, null)
			),
			ErrorCode.MATERIAL_NOT_FOUND
		);
		assertError(
			() -> noteService.listBySession(1L, 100L, 0, 50),
			ErrorCode.MATERIAL_NOT_FOUND
		);
		assertError(
			() -> noteService.listByMaterial(1L, 10L, 0, 50),
			ErrorCode.MATERIAL_NOT_FOUND
		);
	}

	@Test
	void updatesAndDeletesOwnedNotesWhileHidingOtherUsersNotes() {
		Note note = persisted(
			Note.create(user, material, session, null, null, "이전 내용"),
			1000L
		);
		when(noteRepository.findByIdAndUser_Id(1000L, 1L))
			.thenReturn(Optional.of(note));

		var updated = noteService.update(
			1L,
			1000L,
			new UpdateNoteRequest("수정 내용")
		);
		noteService.delete(1L, 1000L);

		assertThat(updated.content()).isEqualTo("수정 내용");
		verify(noteRepository).delete(note);

		when(noteRepository.findByIdAndUser_Id(2000L, 1L))
			.thenReturn(Optional.empty());
		assertError(
			() -> noteService.update(
				1L,
				2000L,
				new UpdateNoteRequest("수정")
			),
			ErrorCode.NOTE_NOT_FOUND
		);
		assertError(
			() -> noteService.delete(1L, 2000L),
			ErrorCode.NOTE_NOT_FOUND
		);
	}

	private void stubSessionAndAccessibleMaterial() {
		when(sessionRepository.findByIdAndUser_Id(100L, 1L))
			.thenReturn(Optional.of(session));
		when(materialAccessService.requireAccessible(1L, 10L))
			.thenReturn(material);
	}

	private Note persisted(Note note, Long id) {
		ReflectionTestUtils.setField(note, "id", id);
		ReflectionTestUtils.setField(note, "createdAt", NOW);
		ReflectionTestUtils.setField(note, "updatedAt", NOW);
		return note;
	}

	private void assertError(Runnable operation, ErrorCode errorCode) {
		assertThatThrownBy(operation::run)
			.isInstanceOfSatisfying(BusinessException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}
}
