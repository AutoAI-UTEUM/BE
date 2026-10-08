package io.edupilot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiStreamCancellation;
import io.edupilot.ai.TurnStreamEvent;
import io.edupilot.auth.JwtTokenProvider;
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
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialAccessService;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.material.storage.FileStorage;
import io.edupilot.report.ReportCriterionResult;
import io.edupilot.report.ReportCriterionResultRepository;
import io.edupilot.report.ReportCriterionStatus;
import io.edupilot.report.ReportEvidenceSnapshot;
import io.edupilot.report.ReportEvidenceSnapshotRepository;
import io.edupilot.report.ReportGeneration;
import io.edupilot.report.ReportGenerationRepository;
import io.edupilot.report.ReportScopeType;
import io.edupilot.report.StudentReport;
import io.edupilot.report.StudentReportRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

/**
 * Committed adult fixtures, real JWT/HTTP/JPA/storage boundaries and mocked external AI only.
 * Adds paired resources, completed report scope chains and isolated connected streams to the
 * earlier single-resource access and revocation suites. The page-text opt-in is test-local;
 * stored renders have no public image API.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:resource-isolation-acceptance;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
	"spring.datasource.username=sa", "spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.flyway.enabled=false",
	"spring.jpa.hibernate.ddl-auto=create-drop", "edupilot.cors.allowed-origins=http://localhost:5173",
	"edupilot.ai.base-url=http://localhost:8000", "edupilot.ai.internal-token=synthetic-internal-token",
	"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
	"edupilot.material.page-text-api-enabled=true",
	"edupilot.mail.enabled=false", "edupilot.mail.provider=logging",
	"edupilot.guardian.web.enabled=false", "edupilot.guardian.team.enabled=false"
})
@ActiveProfiles("jpa-context")
class ResourceIsolationAcceptanceJpaTest {
	private static final Path STORAGE_ROOT = Path.of(
		"build", "test-storage", "resource-isolation-" + UUID.randomUUID()
	).toAbsolutePath().normalize();

	@DynamicPropertySource
	static void isolatedStorage(DynamicPropertyRegistry properties) {
		properties.add("edupilot.storage.root-directory", () -> STORAGE_ROOT.toString());
	}

	@Autowired private WebApplicationContext context;
	@Autowired private UserRepository users;
	@Autowired private ClassroomRepository classrooms;
	@Autowired private ClassroomMemberRepository members;
	@Autowired private ClassroomWeekRepository weeks;
	@Autowired private ClassroomWeekMaterialRepository links;
	@Autowired private LearningMaterialRepository materials;
	@Autowired private MaterialPageRepository pages;
	@Autowired private ClassroomResourceRepository resources;
	@Autowired private ReportGenerationRepository generations;
	@Autowired private StudentReportRepository reports;
	@Autowired private ReportCriterionResultRepository criteria;
	@Autowired private ReportEvidenceSnapshotRepository evidence;
	@Autowired private LearningSessionRepository sessions;
	@Autowired private MaterialAccessService materialAccess;
	@Autowired private SessionStreamAccessGuard streamAccess;
	@Autowired private JwtTokenProvider tokens;
	@Autowired private JdbcTemplate jdbc;
	@MockitoBean private AiClient ai;
	@MockitoSpyBean private FileStorage files;

	private final List<String> storedKeys = new ArrayList<>();
	private final Map<Long, String> issuedTokens = new java.util.HashMap<>();
	private MockMvc mvc;
	private World a;
	private World b;
	private User admin;
	private SessionStreamService connectedStreams;

	@BeforeEach
	void createCommittedPair() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		admin = actor(UserRole.ADMIN);
		a = world("A");
		b = world("B");
		clearInvocations(files);
	}

	@AfterEach
	void closeSyntheticResources() throws IOException {
		if (connectedStreams != null) connectedStreams.shutdown();
		verifyNoInteractions(ai);
		for (String key : storedKeys) {
			files.delete(key);
			if (key.contains("-pages/")) {
				Path directory = STORAGE_ROOT.resolve(key).getParent();
				if (Files.exists(directory)) Files.delete(directory);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(FileRoute.class)
	void fileAndOptInPageRoutesKeepBothClassroomsIsolated(FileRoute route) throws Exception {
		for (World target : List.of(a, b)) {
			World foreign = target == a ? b : a;
			assertAllowedFile(target.instructor(), target, route);
			assertAllowedFile(target.student(), target, route);
			clearInvocations(files);
			assertDenied(bearer(foreign.instructor()), route.path(target), 404,
				route == FileRoute.ATTACHMENT ? "CLASSROOM_NOT_FOUND" : "MATERIAL_NOT_FOUND");
			assertDenied(bearer(foreign.student()), route.path(target), 404,
				route == FileRoute.ATTACHMENT ? "CLASSROOM_NOT_FOUND" : "MATERIAL_NOT_FOUND");
			assertDenied(bearer(admin), route.path(target),
				route == FileRoute.ATTACHMENT ? 403 : 404,
				route == FileRoute.ATTACHMENT ? "ACCESS_DENIED" : "MATERIAL_NOT_FOUND");
			verifyNoInteractions(files);
		}
	}

	@Test
	void anonymousRequestsCannotReadPdfPageTextAttachmentOrCompletedReport() throws Exception {
		for (World target : List.of(a, b)) {
			for (FileRoute route : FileRoute.values()) {
				assertDenied(null, route.path(target), 401, "AUTHENTICATION_REQUIRED");
			}
			assertDenied(null, detailPath(target.full2()), 401, "AUTHENTICATION_REQUIRED");
			assertDenied(null, listPath(target), 401, "AUTHENTICATION_REQUIRED");
		}
		verifyNoInteractions(files);
	}

	@Test
	void storedRendersAndStorageKeysHaveNoHttpServingRouteEvenForOwners() throws Exception {
		for (World target : List.of(a, b)) {
			assertThat(STORAGE_ROOT.resolve(target.renderKey())).isRegularFile();
			List<String> guessedPaths = List.of(
				"/api/materials/" + target.material().getId() + "/pages/1/image",
				"/" + target.renderKey(),
				"/" + target.pdfKey(),
				"/" + target.attachmentKey()
			);
			for (String path : guessedPaths) {
				assertMissingRoute(null, path, path.startsWith("/api/") ? 401 : 404, target.marker());
				for (User actor : List.of(a.instructor(), a.student(), b.instructor(), b.student(), admin)) {
					assertMissingRoute(bearer(actor), path, 404, target.marker());
				}
			}
		}
		verifyNoInteractions(files);
	}

	@Test
	void completedFullAndWeekVersionsExposeOnlyTheirOwnReferencedEvidence() throws Exception {
		for (World target : List.of(a, b)) {
			World foreign = target == a ? b : a;
			mvc.perform(get(listPath(target)).header(HttpHeaders.AUTHORIZATION, bearer(target.instructor())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.items.length()").value(3))
				.andExpect(jsonPath("$.data.items[*].reportId").value(containsInAnyOrder(
					target.full1().getGenerationId().toString(),
					target.full2().getGenerationId().toString(),
					target.week1().getGenerationId().toString()
				)))
				.andExpect(jsonPath("$.data.items[0].version").value(2))
				.andExpect(jsonPath("$.data.activeGeneration").isEmpty());
			mvc.perform(get(detailPath(target.full2()))
					.header(HttpHeaders.AUTHORIZATION, bearer(target.instructor())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.version").value(2))
				.andExpect(jsonPath("$.data.previousVersion").value(1));
			mvc.perform(get(detailPath(target.week1()))
					.header(HttpHeaders.AUTHORIZATION, bearer(target.instructor())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.version").value(1))
				.andExpect(jsonPath("$.data.previousVersion").isEmpty());
			for (StudentReport report : target.versions()) {
				var response = mvc.perform(get(detailPath(report))
						.header(HttpHeaders.AUTHORIZATION, bearer(target.instructor())))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data.status").value("COMPLETED"))
					.andExpect(jsonPath("$.data.evidence.length()").value(1))
					.andExpect(jsonPath("$.data.evidence[0].evidenceId").value("shared-evidence"))
					.andExpect(jsonPath("$.data.evidence[0].publicLabel").value(
						target.marker() + " " + report.getScopeKey() + " v" + report.getVersion()
					))
					.andExpect(jsonPath("$.data.evidence[0].metrics.length()").value(1))
					.andReturn().getResponse().getContentAsString();
				assertThat(response).doesNotContain(
					foreign.marker(), "unreferenced-evidence", "sourceRef", "sourceHash",
					"minimalFact", "synthetic-private-source", "synthetic-private-fact"
				);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(UserRole.class)
	void completedReportDetailsAndVersionListsRejectForeignOwnersAndNonInstructorRoles(UserRole role)
		throws Exception {
		for (World target : List.of(a, b)) {
			World foreign = target == a ? b : a;
			List<User> rejected = switch (role) {
				case INSTRUCTOR -> List.of(foreign.instructor());
				case LEARNER -> List.of(target.student(), foreign.student());
				case ADMIN -> List.of(admin);
			};
			for (User actor : rejected) {
				for (StudentReport report : target.versions()) {
					assertDenied(bearer(actor), detailPath(report),
						role == UserRole.INSTRUCTOR ? 404 : 403,
						role == UserRole.INSTRUCTOR ? "CLASSROOM_NOT_FOUND" : "ACCESS_DENIED");
				}
				assertDenied(bearer(actor), listPath(target),
					role == UserRole.INSTRUCTOR ? 404 : 403,
					role == UserRole.INSTRUCTOR ? "CLASSROOM_NOT_FOUND" : "ACCESS_DENIED");
			}
		}
	}

	@Test
	void versionListChecksBothClassroomOwnerAndStudentMembershipWhenReferencesAreMixed() throws Exception {
		for (World target : List.of(a, b)) {
			World foreign = target == a ? b : a;
			assertDenied(bearer(target.instructor()),
				listPath(target.classroom().getId(), foreign.student().getId()), 404, "REPORT_NOT_FOUND");
			assertDenied(bearer(target.instructor()),
				listPath(foreign.classroom().getId(), target.student().getId()), 404, "CLASSROOM_NOT_FOUND");
		}
	}

	@Test
	void removingOneMembershipRevokesAllItsFileAndReportVersionsWithoutAffectingTheOtherClassroom()
		throws Exception {
		String learnerToken = bearer(a.student());
		String instructorToken = bearer(a.instructor());
		for (FileRoute route : FileRoute.values()) assertAllowedFile(a.student(), a, route);
		mvc.perform(get(detailPath(a.full2())).header(HttpHeaders.AUTHORIZATION, instructorToken))
			.andExpect(status().isOk());

		mvc.perform(delete("/api/classrooms/" + a.classroom().getId() + "/students/" + a.student().getId())
				.header(HttpHeaders.AUTHORIZATION, instructorToken))
			.andExpect(status().isOk());
		assertThat(members.existsByClassroom_IdAndUser_Id(a.classroom().getId(), a.student().getId())).isFalse();
		assertThat(reports.findByGeneration_Id(a.full2().getGenerationId())).isPresent();
		clearInvocations(files);

		for (FileRoute route : FileRoute.values()) {
			assertDenied(learnerToken, route.path(a), 404,
				route == FileRoute.ATTACHMENT ? "CLASSROOM_NOT_FOUND" : "MATERIAL_NOT_FOUND");
		}
		for (StudentReport report : a.versions()) {
			assertDenied(instructorToken, detailPath(report), 404, "REPORT_NOT_FOUND");
		}
		assertDenied(instructorToken, listPath(a), 404, "REPORT_NOT_FOUND");
		verifyNoInteractions(files);
		for (FileRoute route : FileRoute.values()) {
			assertAllowedFile(a.instructor(), a, route);
			assertAllowedFile(b.student(), b, route);
		}
		mvc.perform(get(detailPath(b.full2())).header(HttpHeaders.AUTHORIZATION, bearer(b.instructor())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.evidence[0].publicLabel").value(b.marker() + " FULL v2"));
	}

	@ParameterizedTest
	@EnumSource(AccountRevocation.class)
	void committedAccountChangesRejectPreviouslyIssuedTokensAcrossTheNewRoutes(AccountRevocation change)
		throws Exception {
		User actor = change == AccountRevocation.LEARNER_SUSPENDED ? a.student() : a.instructor();
		String oldToken = bearer(actor);
		for (FileRoute route : FileRoute.values()) assertAllowedFile(actor, a, route);
		if (actor == a.instructor()) {
			mvc.perform(get(detailPath(a.week1())).header(HttpHeaders.AUTHORIZATION, oldToken))
				.andExpect(status().isOk());
		}
		switch (change) {
			case INSTRUCTOR_ROLE_CHANGED -> jdbc.update("update users set role='LEARNER' where id=?", actor.getId());
			case INSTRUCTOR_WITHDRAWN -> jdbc.update("update users set status='DELETED' where id=?", actor.getId());
			case LEARNER_SUSPENDED -> jdbc.update("update users set status='SUSPENDED' where id=?", actor.getId());
		}
		clearInvocations(files);
		String error = change == AccountRevocation.LEARNER_SUSPENDED ? "ACCOUNT_SUSPENDED" : "TOKEN_INVALID";
		for (FileRoute route : FileRoute.values()) assertDenied(oldToken, route.path(a), 401, error);
		if (actor == a.instructor()) {
			for (StudentReport report : a.versions()) assertDenied(oldToken, detailPath(report), 401, error);
			assertDenied(oldToken, listPath(a), 401, error);
		}
		verifyNoInteractions(files);
		for (FileRoute route : FileRoute.values()) assertAllowedFile(b.student(), b, route);
		mvc.perform(get(detailPath(b.week1())).header(HttpHeaders.AUTHORIZATION, bearer(b.instructor())))
			.andExpect(status().isOk());
	}

	@ParameterizedTest
	@EnumSource(ResourceRevocation.class)
	void revokingOneResourceClosesOnlyThatStreamWhileTheSameLearnersOtherResourceKeepsDelivering(
		ResourceRevocation change
	) throws Exception {
		// The same actor has an independently committed grant to B for this test only.
		members.saveAndFlush(ClassroomMember.create(b.classroom(), a.student(), Instant.now()));
		LearningSession secondSession = sessions.saveAndFlush(LearningSession.create(a.student(), b.material()));
		RecordingEmitter first = new RecordingEmitter();
		RecordingEmitter second = new RecordingEmitter();
		AtomicInteger factoryCalls = new AtomicInteger();
		connectedStreams = new SessionStreamService(sessions, materialAccess, streamAccess,
			() -> factoryCalls.getAndIncrement() == 0 ? first : second);
		connectedStreams.connect(a.student().getId(), a.session().getId());
		connectedStreams.connect(a.student().getId(), secondSession.getId());
		AiStreamCancellation firstUpstream = new AiStreamCancellation();
		AiStreamCancellation secondUpstream = new AiStreamCancellation();
		SessionStreamConnection firstConnection = connectedStreams.beginTurn(
			a.student().getId(), a.session().getId(), "synthetic-A-turn", firstUpstream
		).orElseThrow();
		SessionStreamConnection secondConnection = connectedStreams.beginTurn(
			a.student().getId(), secondSession.getId(), "synthetic-B-turn", secondUpstream
		).orElseThrow();
		firstConnection.send(TurnStreamEvent.contentDelta(a.marker()));
		secondConnection.send(TurnStreamEvent.contentDelta(b.marker()));
		int firstDelivered = first.deliveries.get();
		int secondDelivered = second.deliveries.get();
		assertThat(connectedStreams.beginTurn(b.student().getId(), secondSession.getId(),
			"synthetic-foreign-turn", new AiStreamCancellation())).isEmpty();

		switch (change) {
			case MEMBERSHIP_REMOVED -> members.deleteById(a.member().getId());
			case LINK_REMOVED -> links.deleteById(a.link().getId());
		}

		assertThatThrownBy(() -> firstConnection.send(TurnStreamEvent.contentDelta("synthetic-revoked")))
			.isInstanceOf(AiClientException.class);
		assertThat(first.deliveries.get()).isEqualTo(firstDelivered);
		assertThat(firstConnection.closeReason()).isEqualTo(SessionStreamConnection.CloseReason.ACCESS_REVOKED);
		assertThat(firstUpstream.isCancelled()).isTrue();
		assertThat(firstUpstream.isUserCancelled()).isFalse();
		secondConnection.send(TurnStreamEvent.contentDelta("synthetic-B-still-authorized"));
		assertThat(second.deliveries.get()).isEqualTo(secondDelivered + 1);
		assertThat(secondConnection.isClosed()).isFalse();
		assertThat(secondUpstream.isCancelled()).isFalse();
		assertThat(connectedStreams.beginTurn(a.student().getId(), a.session().getId(),
			"synthetic-A-after-revocation", new AiStreamCancellation())).isEmpty();
		assertAllowedFile(a.student(), b, FileRoute.ATTACHMENT);
		assertDenied(bearer(a.student()), FileRoute.PAGE_TEXT.path(a), 404, "MATERIAL_NOT_FOUND");
	}

	private World world(String label) {
		User instructor = actor(UserRole.INSTRUCTOR);
		User student = actor(UserRole.LEARNER);
		String marker = "Synthetic-" + label + "-" + UUID.randomUUID();
		Classroom classroom = classrooms.saveAndFlush(Classroom.create(instructor, marker,
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			UUID.randomUUID().toString().substring(0, 10)));
		ClassroomMember member = members.saveAndFlush(ClassroomMember.create(classroom, student, Instant.now()));
		ClassroomWeek week = weeks.saveAndFlush(ClassroomWeek.create(
			classroom, 1, marker + " week", null, ClassroomWeekStatus.PUBLISHED, 1));
		String pdfKey = store(marker + " PDF", false);
		LearningMaterial material = LearningMaterial.create(instructor, marker + " PDF", pdfKey);
		material.markReady(1);
		material = materials.saveAndFlush(material);
		pages.saveAndFlush(MaterialPage.create(material, 1, marker + " page text"));
		ClassroomWeekMaterial link = links.saveAndFlush(ClassroomWeekMaterial.create(week, material, Instant.now()));
		String renderKey = pdfKey.substring(0, pdfKey.length() - 4) + "-pages/1.jpg";
		files.storePageImage(new ByteArrayInputStream((marker + " JPEG").getBytes(StandardCharsets.UTF_8)), renderKey);
		storedKeys.add(renderKey);
		String attachmentKey = store(marker + " attachment", true);
		ClassroomResource attachment = resources.saveAndFlush(ClassroomResource.file(
			classroom, marker + " attachment", 1, "synthetic.txt", "text/plain",
			(marker + " attachment").getBytes(StandardCharsets.UTF_8).length, attachmentKey));
		LearningSession session = sessions.saveAndFlush(LearningSession.create(student, material));
		StudentReport full1 = completedReport(classroom, student, instructor, marker, ReportScopeType.FULL, 1, null);
		StudentReport full2 = completedReport(classroom, student, instructor, marker, ReportScopeType.FULL, 2, full1);
		StudentReport week1 = completedReport(classroom, student, instructor, marker, ReportScopeType.WEEK, 1, null);
		return new World(instructor, student, classroom, member, material, link, attachment,
			session, marker, pdfKey, renderKey, attachmentKey, full1, full2, week1);
	}

	private StudentReport completedReport(Classroom classroom, User student, User instructor,
		String marker, ReportScopeType scope, int version, StudentReport previous) {
		ReportGeneration generation = ReportGeneration.create(classroom, student, instructor,
			UUID.randomUUID().toString(), scope, scope == ReportScopeType.WEEK ? 1 : null, "a".repeat(64), "1.0");
		generation.complete("synthetic-model", "synthetic-v1");
		generation = generations.saveAndFlush(generation);
		StudentReport report = reports.saveAndFlush(StudentReport.create(
			generation, classroom, student, version, previous, new BigDecimal("75.00"), "DEVELOPING",
			"{\"summary\":\"Synthetic summary\"}", Map.of(), "synthetic-model", "synthetic-v1"));
		criteria.saveAndFlush(ReportCriterionResult.create(report, "synthetic-concept", 1,
			new BigDecimal("75.00"), null, ReportCriterionStatus.ASSESSED, "Synthetic narrative",
			List.of("shared-evidence")));
		String publicLabel = marker + " " + generation.getScopeKey() + " v" + version;
		// Reusing the evidence ID in every generation detects joins that lose generation scope.
		evidence.saveAndFlush(ReportEvidenceSnapshot.create(generation, "shared-evidence", "QUIZ",
			"synthetic-private-source", Instant.parse("2026-01-01T00:00:00Z"), publicLabel,
			Map.of("normalizedScore", 75, "privateFact", "synthetic-private-fact"), "b".repeat(64)));
		evidence.saveAndFlush(ReportEvidenceSnapshot.create(generation, "unreferenced-evidence", "QUIZ",
			"synthetic-private-source", Instant.parse("2026-01-01T00:00:01Z"), marker + " unreferenced",
			Map.of("normalizedScore", 25), "c".repeat(64)));
		return report;
	}

	private User actor(UserRole role) {
		User actor = User.create("synthetic-" + UUID.randomUUID() + "@example.test", "!synthetic", "Synthetic actor", role);
		actor.recordSignupDateOfBirth(LocalDate.of(1990, 1, 1));
		actor.verifyEmail(Instant.parse("2020-01-01T00:00:00Z"));
		return users.saveAndFlush(actor);
	}

	private String store(String payload, boolean attachment) {
		ByteArrayInputStream bytes = new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8));
		String key = attachment ? files.storeClassroomResource(bytes) : files.store(bytes);
		storedKeys.add(key);
		return key;
	}

	private String bearer(User actor) {
		return issuedTokens.computeIfAbsent(actor.getId(), id -> "Bearer " + tokens.createAccessToken(actor));
	}

	private void assertAllowedFile(User actor, World target, FileRoute route) throws Exception {
		var result = mvc.perform(get(route.path(target)).header(HttpHeaders.AUTHORIZATION, bearer(actor)))
			.andExpect(status().isOk()).andReturn().getResponse();
		if (route == FileRoute.PAGE_TEXT) {
			assertThat(result.getContentAsString()).contains(target.marker() + " page text");
		} else {
			assertThat(result.getContentAsByteArray()).isEqualTo(
				(target.marker() + (route == FileRoute.PDF ? " PDF" : " attachment")).getBytes(StandardCharsets.UTF_8));
			assertThat(result.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, max-age=3600, immutable");
		}
	}

	private void assertDenied(String authorization, String path, int expectedStatus, String error) throws Exception {
		var request = get(path);
		if (authorization != null) request.header(HttpHeaders.AUTHORIZATION, authorization);
		var response = mvc.perform(request).andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value(error))
			.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain(a.marker(), b.marker(),
			"synthetic-private-source", "synthetic-private-fact", "Synthetic narrative");
	}

	private void assertMissingRoute(String authorization, String path, int expectedStatus, String marker) throws Exception {
		var request = get(path);
		if (authorization != null) request.header(HttpHeaders.AUTHORIZATION, authorization);
		var response = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse();
		assertThat(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8)).doesNotContain(marker);
	}

	private static String detailPath(StudentReport report) {
		return "/api/reports/" + report.getGenerationId();
	}

	private static String listPath(World world) {
		return listPath(world.classroom().getId(), world.student().getId());
	}

	private static String listPath(Long classroomId, Long studentId) {
		return "/api/classrooms/" + classroomId + "/students/" + studentId + "/reports";
	}

	private enum FileRoute {
		PDF, PAGE_TEXT, ATTACHMENT;
		String path(World world) {
			return switch (this) {
				case PDF -> "/api/materials/" + world.material().getId() + "/file";
				case PAGE_TEXT -> "/api/materials/" + world.material().getId() + "/pages/1";
				case ATTACHMENT -> "/api/resources/" + world.attachment().getId() + "/file";
			};
		}
	}

	private enum AccountRevocation { INSTRUCTOR_ROLE_CHANGED, INSTRUCTOR_WITHDRAWN, LEARNER_SUSPENDED }
	private enum ResourceRevocation { MEMBERSHIP_REMOVED, LINK_REMOVED }

	private record World(User instructor, User student, Classroom classroom, ClassroomMember member,
		LearningMaterial material, ClassroomWeekMaterial link, ClassroomResource attachment, LearningSession session,
		String marker, String pdfKey, String renderKey, String attachmentKey,
		StudentReport full1, StudentReport full2, StudentReport week1) {
		List<StudentReport> versions() { return List.of(full1, full2, week1); }
	}

	private static final class RecordingEmitter extends SseEmitter {
		private final AtomicInteger deliveries = new AtomicInteger();
		@Override public void send(SseEventBuilder event) throws IOException { deliveries.incrementAndGet(); }
	}
}
