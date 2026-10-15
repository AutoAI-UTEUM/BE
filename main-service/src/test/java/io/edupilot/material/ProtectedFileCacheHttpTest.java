package io.edupilot.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.edupilot.ai.AiClient;
import io.edupilot.auth.GoogleIdTokenVerifier;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomResource;
import io.edupilot.classroom.ClassroomResourceRepository;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.guardian.GuardianPhoneProvider;
import io.edupilot.mail.EmailSender;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

/** Real loopback HTTP, login, current authorization, JPA and storage; all actors/files are synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"server.address=127.0.0.1",
	"spring.datasource.url=jdbc:h2:mem:protected-file-cache;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop",
	"edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://127.0.0.1:1", "edupilot.ai.internal-token=synthetic-only",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging",
	"edupilot.mail.outbox.dispatch.mode=PAUSED",
	"edupilot.guardian.web.enabled=false", "edupilot.guardian.team.enabled=false",
	"edupilot.deletion.enabled=false", "edupilot.admin.infra.enabled=false"
})
@ActiveProfiles("jpa-context")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(90)
class ProtectedFileCacheHttpTest {
	private static final String PASSWORD = "SyntheticFileCache!only";
	private static final Path STORAGE = Path.of("build", "test-storage",
		"protected-file-cache-" + UUID.randomUUID()).toAbsolutePath().normalize();
	private static final ObjectMapper JSON = new ObjectMapper();

	@DynamicPropertySource
	static void isolatedStorage(DynamicPropertyRegistry properties) {
		properties.add("edupilot.storage.root-directory", () -> STORAGE.toString());
	}

	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private ClassroomResourceRepository resources;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private PasswordEncoder passwords;
	@Autowired private PlatformTransactionManager transactions;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private FileStorage storage;
	@MockitoBean private AiClient ai;
	@MockitoBean private EmailSender email;
	@MockitoBean private GuardianPhoneProvider phone;
	@MockitoBean private GoogleIdTokenVerifier google;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<String> storageKeys = new ArrayList<>();
	private World a;
	private World b;

	@BeforeEach
	void createCommittedPair() {
		assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
			.isEqualToIgnoringCase("protected-file-cache");
		new TransactionTemplate(transactions).executeWithoutResult(tx -> {
			a = world("A");
			b = world("B");
		});
	}

	@AfterEach
	void closeOwnedFiles() throws Exception {
		for (String key : storageKeys) storage.delete(key);
		http.shutdownNow();
		assertThat(http.awaitTermination(Duration.ofSeconds(5))).isTrue();
		verifyNoInteractions(ai, email, phone, google);
	}

	@Test
	void fullHeadAndRangeResponsesForbidStorageWithoutChangingFileContracts() throws Exception {
		String token = login(a.student());
		for (FileCase file : files(a)) {
			var full = request("GET", file.path(), token, null);
			assertThat(full.statusCode()).isEqualTo(200);
			assertHeaders(full, file);
			assertThat(full.body()).isEqualTo(file.bytes());

			var head = request("HEAD", file.path(), token, null);
			assertThat(head.statusCode()).isEqualTo(200);
			assertHeaders(head, file);
			assertThat(head.body()).isEmpty();

			var range = request("GET", file.path(), token, "bytes=0-2");
			assertThat(range.statusCode()).isEqualTo(206);
			assertHeaders(range, file);
			assertThat(range.headers().firstValue("Content-Range")).contains("bytes 0-2/" + file.bytes().length);
			assertThat(range.body()).isEqualTo(java.util.Arrays.copyOf(file.bytes(), 3));
		}
	}

	@Test
	void sameTokenMembershipRemovalAndDifferentAccountStillUseCurrentOriginAuthorization() throws Exception {
		String student = login(a.student());
		String owner = login(a.instructor());
		String foreign = login(b.student());
		for (FileCase file : files(a)) {
			assertThat(request("GET", file.path(), student, null).statusCode()).isEqualTo(200);
			assertThat(request("GET", file.path(), foreign, null).statusCode()).isEqualTo(404);
			assertThat(request("GET", file.path(), null, null).statusCode()).isEqualTo(401);
		}
		assertThat(request("DELETE", "/api/classrooms/" + a.classroomId() + "/students/" + a.student().id(), owner, null)
			.statusCode()).isEqualTo(200);
		for (FileCase file : files(a)) {
			var revoked = request("GET", file.path(), student, null);
			assertThat(revoked.statusCode()).isEqualTo(404);
			assertThat(revoked.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
			assertThat(new String(revoked.body(), StandardCharsets.UTF_8))
				.doesNotContain(new String(file.bytes(), StandardCharsets.UTF_8));
			var allowed = request("GET", file.path(), owner, null);
			assertThat(allowed.statusCode()).isEqualTo(200);
			assertHeaders(allowed, file);
			assertThat(allowed.body()).isEqualTo(file.bytes());
		}
	}

	/** Optional cross-repository run uses the FE's committed browser suite against this real origin. */
	@Test
	@Timeout(value = 5, unit = TimeUnit.MINUTES)
	@EnabledIfEnvironmentVariable(named = "FILE_CACHE_FE_ROOT", matches = ".+")
	void actualFrontendRepositoriesBypassWarmedLegacyBrowserEntries() throws Exception {
		Path frontend = Path.of(System.getenv("FILE_CACHE_FE_ROOT")).toAbsolutePath().normalize();
		assertThat(frontend.resolve("playwright.file-cache.config.ts")).isRegularFile();
		ProcessBuilder command = new ProcessBuilder(
			System.getenv().getOrDefault("FILE_CACHE_NODE", "node"),
			"node_modules/@playwright/test/cli.js", "test", "--config", "playwright.file-cache.config.ts"
		).directory(frontend.toFile()).inheritIO();
		command.environment().put("FILE_CACHE_BACKEND_ORIGIN", "http://127.0.0.1:" + port);
		command.environment().put("FILE_CACHE_BACKEND_FIXTURES", JSON.writeValueAsString(List.of(a, b)));
		command.environment().put("FILE_CACHE_PASSWORD", PASSWORD);
		Process browser = command.start();
		try {
			assertThat(browser.waitFor(210, TimeUnit.SECONDS)).as("FE browser suite completes").isTrue();
			assertThat(browser.exitValue()).as("FE browser suite exit").isZero();
		} finally {
			if (browser.isAlive()) browser.destroyForcibly();
		}
	}

	private void assertHeaders(HttpResponse<byte[]> response, FileCase file) {
		assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store, private");
		assertThat(response.headers().firstValue("Content-Type")).contains(file.mediaType());
		assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith(file.disposition());
		assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
		if (file.disposition().equals("attachment")) {
			assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("filename*=UTF-8''");
		}
	}

	private String login(Actor actor) throws Exception {
		var response = http.send(HttpRequest.newBuilder(uri("/api/auth/login"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
				java.util.Map.of("email", actor.email(), "password", PASSWORD))))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as("Synthetic login").isEqualTo(200);
		return JSON.readTree(response.body()).path("data").path("accessToken").asText();
	}

	private HttpResponse<byte[]> request(String method, String path, String token, String range) throws Exception {
		var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15))
			.method(method, HttpRequest.BodyPublishers.noBody());
		if (path.startsWith("/api/materials/") && path.endsWith("/file")) {
			request.header("Accept", "application/pdf, application/json");
		}
		if (token != null) request.header("Authorization", "Bearer " + token);
		if (range != null) request.header("Range", range);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

	private List<FileCase> files(World world) {
		return List.of(
			new FileCase("/api/materials/" + world.materialId() + "/file", world.pdf().getBytes(StandardCharsets.UTF_8),
				"application/pdf", "inline"),
			new FileCase("/api/resources/" + world.attachmentId() + "/file", world.attachment().getBytes(StandardCharsets.UTF_8),
				"text/plain", "attachment")
		);
	}

	private World world(String label) {
		Actor instructor = actor(UserRole.INSTRUCTOR);
		Actor learner = actor(UserRole.LEARNER);
		User owner = users.getReferenceById(instructor.id());
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(owner, "Synthetic " + label,
			LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		members.saveAndFlush(ClassroomMember.create(classroom, users.getReferenceById(learner.id()), Instant.now()));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(classroom, 1, "Synthetic week", null,
			ClassroomWeekStatus.PUBLISHED, 1));
		String pdf = "%PDF-1.7\nSYNTHETIC_CACHE_" + label + "_" + UUID.randomUUID();
		String attachment = "SYNTHETIC_ATTACHMENT_" + label + "_" + UUID.randomUUID();
		String pdfKey = store(pdf, false);
		LearningMaterial material = LearningMaterial.create(owner, "Synthetic PDF", pdfKey);
		material.markReady(1);
		material = materials.saveAndFlush(material);
		links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		ClassroomResource resource = resources.saveAndFlush(ClassroomResource.file(classroom,
			"Synthetic attachment", 1, "합성 자료.txt", "text/plain", attachment.getBytes(StandardCharsets.UTF_8).length,
			store(attachment, true)));
		return new World(label, instructor, learner, classroom.getId(), material.getId(), resource.getId(),
			pdf, attachment, digest(pdf), digest(attachment));
	}

	private Actor actor(UserRole role) {
		String emailAddress = "file-cache-" + UUID.randomUUID() + "@example.test";
		User user = User.create(emailAddress, passwords.encode(PASSWORD), "Synthetic adult", role);
		user.recordSignupDateOfBirth(LocalDate.of(1990, 1, 1));
		user.verifyEmail(Instant.now().minusSeconds(60));
		return new Actor(users.saveAndFlush(user).getId(), emailAddress);
	}

	private String store(String value, boolean attachment) {
		var bytes = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
		String key = attachment ? storage.storeClassroomResource(bytes) : storage.store(bytes);
		storageKeys.add(key);
		return key;
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
	}

	private record Actor(long id, String email) { }
	private record World(String label, Actor instructor, Actor student, long classroomId, long materialId,
		long attachmentId, String pdf, String attachment, String pdfSha256, String attachmentSha256) { }
	private record FileCase(String path, byte[] bytes, String mediaType, String disposition) { }
}
