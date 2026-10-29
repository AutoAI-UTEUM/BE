package io.edupilot.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.ai.AiClient;
import io.edupilot.global.error.BusinessException;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.note.dto.CreateNoteRequest;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:note-idempotency;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/note-idempotency"
})
@ActiveProfiles("jpa-context")
class NoteIdempotencyJpaTest {
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private ChatMessageRepository messages;
	@Autowired private NoteRepository notes;
	@Autowired private NoteService service;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;

	@Test
	void concurrentAiNoteSavesReturnOneCommittedNote() throws Exception {
		Fixture f = fixture();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<Long> save = () -> {
				ready.countDown();
				assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
				return service.create(f.user(), f.session(), new CreateNoteRequest("Synthetic AI note", 1, f.message())).noteId();
			};
			var first = executor.submit(save);
			var second = executor.submit(save);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
		}
		assertThat(count(f)).isEqualTo(1);
	}

	@Test
	void legacyDuplicatesAreRetainedAndEarliestEditedNoteIsReplayed() {
		Fixture f = fixture();
		for (String content : java.util.List.of("Edited legacy", "Other legacy")) {
			jdbc.update("""
				insert into notes(user_id, material_id, session_id, source_message_id, content, created_at, updated_at)
				values (?, ?, ?, ?, ?, current_timestamp, current_timestamp)
				""", f.user(), f.material(), f.session(), f.message(), content);
		}
		var replay = service.create(f.user(), f.session(), new CreateNoteRequest("Original AI draft", 1, f.message()));
		assertThat(replay.content()).isEqualTo("Edited legacy");
		assertThat(count(f)).isEqualTo(2);
	}

	@Test
	void manualNotesRemainIndependentAndOtherUsersCannotReplaySource() {
		Fixture f = fixture();
		var first = service.create(f.user(), f.session(), new CreateNoteRequest("Same manual text", 1, null));
		var second = service.create(f.user(), f.session(), new CreateNoteRequest("Same manual text", 1, null));
		assertThat(first.noteId()).isNotEqualTo(second.noteId());
		Fixture other = fixture();
		assertThatThrownBy(() -> service.create(other.user(), f.session(), new CreateNoteRequest("Synthetic", 1, f.message())))
			.isInstanceOfSatisfying(BusinessException.class,
				error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.SESSION_NOT_FOUND));
	}

	private long count(Fixture f) {
		return jdbc.queryForObject("select count(*) from notes where user_id = ? and source_message_id = ?", Long.class, f.user(), f.message());
	}

	private Fixture fixture() {
		User user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "Synthetic"));
		LearningMaterial material = LearningMaterial.create(user, "Synthetic", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(2);
		materials.saveAndFlush(material);
		LearningSession session = sessions.saveAndFlush(LearningSession.create(user, material));
		ChatMessage message = messages.saveAndFlush(ChatMessage.ai(session, "Synthetic note draft"));
		return new Fixture(user.getId(), material.getId(), session.getId(), message.getId());
	}

	private record Fixture(Long user, Long material, Long session, Long message) { }
}
