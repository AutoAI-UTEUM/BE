package io.edupilot.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.sql.DriverManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.edupilot.ai.AiClient;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.note.dto.CreateNoteRequest;
import io.edupilot.session.ChatMessage;
import io.edupilot.session.ChatMessageRepository;
import io.edupilot.session.LearningSession;
import io.edupilot.session.LearningSessionRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;

/** Opt-in: only a disposable loopback MySQL database; never a shared/production database. */
@EnabledIfEnvironmentVariable(named = "NOTE_MYSQL_URL",
	matches = "^jdbc:mysql://127\\.0\\.0\\.1:33316/note_synthetic(?:\\?.*)?$")
@SpringBootTest(properties = {
	"spring.datasource.url=${NOTE_MYSQL_URL}",
	"spring.datasource.username=root", "spring.datasource.password=",
	"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=test-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.storage.root-directory=build/test-storage/note-mysql"
})
@ActiveProfiles("jpa-context")
class NoteMysqlIntegrationTest {
	@Autowired private UserRepository users;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private ChatMessageRepository messages;
	@MockitoSpyBean private NoteRepository notes;
	@Autowired private NoteService service;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;

	@BeforeEach
	void emptyNotes() {
		assertThat(jdbc.queryForObject("select @@port", Integer.class)).isEqualTo(33316);
		assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("note_synthetic");
		assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
		notes.deleteAll();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void differentSourcesCanInsertIntoTheSameEmptyIndexGap(boolean differentUsers) throws Exception {
		Fixture first = fixture();
		Fixture second;
		if (differentUsers) {
			second = fixture();
		} else {
			ChatMessage message = messages.saveAndFlush(ChatMessage.ai(
				sessions.findById(first.session()).orElseThrow(), "Another synthetic note draft"));
			second = new Fixture(first.user(), first.session(), message.getId());
		}
		CyclicBarrier afterAbsentNoteRead = new CyclicBarrier(2);
		var realRepositoryAnswer = org.mockito.Mockito.mockingDetails(notes)
			.getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			assertThat(jdbc.queryForObject("select @@transaction_isolation", String.class))
				.isEqualTo("READ-COMMITTED");
			Object result = realRepositoryAnswer.answer(invocation);
			afterAbsentNoteRead.await(10, TimeUnit.SECONDS);
			return result;
		}).when(notes).findFirstByUser_IdAndSourceMessage_IdOrderByIdAsc(anyLong(), anyLong());
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> save(first));
			var b = executor.submit(() -> save(second));
			assertThat(a.get(15, TimeUnit.SECONDS)).isNotEqualTo(b.get(15, TimeUnit.SECONDS));
		}
		assertThat(notes.count()).isEqualTo(2);
	}

	@Test
	void sameSourceWaitsForCommitAndReturnsTheExistingEditedNote() throws Exception {
		Fixture fixture = fixture();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> { start.await(); return save(fixture); });
			var b = executor.submit(() -> { start.await(); return save(fixture); });
			start.countDown();
			Long id = a.get(15, TimeUnit.SECONDS);
			assertThat(b.get(15, TimeUnit.SECONDS)).isEqualTo(id);
			jdbc.update("update notes set content = 'Edited synthetic note' where id = ?", id);
			assertThat(service.create(fixture.user(), fixture.session(),
				new CreateNoteRequest("Original synthetic draft", 1, fixture.message())).content())
				.isEqualTo("Edited synthetic note");
		}
		assertThat(notes.count()).isEqualTo(1);
	}

	@Test
	void actualV53MigrationPreservesLegacyDuplicatesAndEnforcesNewKeys() throws Exception {
		jdbc.execute("create database if not exists note_migration_synthetic");
		String migrationUrl = System.getenv("NOTE_MYSQL_URL").replace("/note_synthetic", "/note_migration_synthetic");
		try (var connection = DriverManager.getConnection(migrationUrl, "root", "")) {
			JdbcTemplate migration = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
			migration.execute("drop table if exists notes");
			migration.execute("create table notes(id bigint auto_increment primary key, user_id bigint not null, source_message_id bigint, content varchar(100)) engine=InnoDB");
			migration.update("insert into notes(user_id,source_message_id,content) values(1,10,'legacy one'),(1,10,'legacy two')");
			ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V53__note_source_idempotency.sql"));
			assertThat(migration.queryForObject("select count(*) from notes", Long.class)).isEqualTo(2);
			migration.update("insert into notes(user_id,source_message_id,dedup_source_message_id,content) values(1,11,11,'new')");
			assertThatThrownBy(() -> migration.update("insert into notes(user_id,source_message_id,dedup_source_message_id,content) values(1,11,11,'duplicate')"))
				.isInstanceOf(DataIntegrityViolationException.class);
			migration.update("insert into notes(user_id,content) values(1,'manual'),(1,'manual again')");
			assertThatThrownBy(() -> migration.update("insert into notes(user_id,source_message_id,dedup_source_message_id,content) values(1,12,13,'wrong source')"))
				.isInstanceOfSatisfying(DataAccessException.class, error ->
					assertThat(((java.sql.SQLException) error.getMostSpecificCause()).getErrorCode()).isEqualTo(3819));
		}
	}

	private Long save(Fixture fixture) {
		return service.create(fixture.user(), fixture.session(),
			new CreateNoteRequest("Synthetic AI note", 1, fixture.message())).noteId();
	}

	private Fixture fixture() {
		User user = users.saveAndFlush(User.create(UUID.randomUUID() + "@example.com", "hash", "Synthetic"));
		LearningMaterial material = LearningMaterial.create(user, "Synthetic", "materials/" + UUID.randomUUID() + ".pdf");
		material.markReady(2);
		materials.saveAndFlush(material);
		LearningSession session = sessions.saveAndFlush(LearningSession.create(user, material));
		ChatMessage message = messages.saveAndFlush(ChatMessage.ai(session, "Synthetic note draft"));
		return new Fixture(user.getId(), session.getId(), message.getId());
	}

	private record Fixture(Long user, Long session, Long message) { }
}
