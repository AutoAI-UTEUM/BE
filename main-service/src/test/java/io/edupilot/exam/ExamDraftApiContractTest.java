package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.dto.ExamDraftRequest;
import io.edupilot.ai.dto.ExamDraftResponse;
import io.edupilot.auth.JwtTokenProvider;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.classroom.ClassroomWeek;
import io.edupilot.classroom.ClassroomWeekMaterial;
import io.edupilot.classroom.ClassroomWeekMaterialRepository;
import io.edupilot.classroom.ClassroomWeekRepository;
import io.edupilot.classroom.ClassroomWeekStatus;
import io.edupilot.global.security.TraceIdFilter;
import io.edupilot.material.LearningMaterial;
import io.edupilot.material.LearningMaterialRepository;
import io.edupilot.material.MaterialPage;
import io.edupilot.material.MaterialPageRepository;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-draft;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-draft"
	}
)
@ActiveProfiles("jpa-context")
class ExamDraftApiContractTest {

	private static final AtomicLong SEQUENCE = new AtomicLong();

	@Autowired private WebApplicationContext context;
	@Autowired private TraceIdFilter traceIdFilter;
	@Autowired private JwtTokenProvider jwtTokenProvider;
	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomWeekRepository weekRepository;
	@Autowired private ClassroomWeekMaterialRepository weekMaterialRepository;
	@Autowired private LearningMaterialRepository materialRepository;
	@Autowired private MaterialPageRepository pageRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;

	@MockitoBean private AiClient aiClient;

	private MockMvc mockMvc;
	private Fixture fixture;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.addFilters(traceIdFilter)
			.build();
		fixture = createFixture();
	}

	@Test
	void returnsTwoTypeDraftWithAnswersWithoutPersistingQuestions() throws Exception {
		when(aiClient.generateExamDraft(any())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive())
				.isFalse();
			return twoTypeResponse(fixture.exam().getId());
		});
		int before = questionRepository.findByExam_IdOrderByQuestionNo(
			fixture.exam().getId()
		).size();

		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(twoTypeRequest(fixture.material().getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.truncated").value(false))
			.andExpect(jsonPath("$.data.questions[0].answerChoiceId").value("a"))
			.andExpect(jsonPath("$.data.questions[0].explanation").value("Because A"))
			.andExpect(jsonPath("$.data.questions[1].referenceAnswer").value("Answer"));

		assertThat(questionRepository.findByExam_IdOrderByQuestionNo(
			fixture.exam().getId()
		)).hasSize(before);
	}

	@Test
	void truncatesThirtyOnePagesAndSendsUniqueSequentialContextNumbers() throws Exception {
		LearningMaterial material = addReadyMaterial(fixture.classroom(), 31, false);
		when(aiClient.generateExamDraft(any())).thenReturn(mcqResponse(fixture.exam().getId(), 1));

		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(material.getId(), 1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.truncated").value(true));

		ArgumentCaptor<ExamDraftRequest> captor = ArgumentCaptor.forClass(
			ExamDraftRequest.class
		);
		verify(aiClient).generateExamDraft(captor.capture());
		assertThat(captor.getValue().pageContexts()).hasSize(30);
		assertThat(captor.getValue().pageContexts())
			.extracting(ExamDraftRequest.PageContext::pageNumber)
			.containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 30)
				.boxed().toList());
	}

	@Test
	void rejectsNonDraftExamWithExistingConflictCode() throws Exception {
		fixture.exam().publish(Instant.now());
		examRepository.saveAndFlush(fixture.exam());

		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.material().getId(), 1)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXAM_NOT_EDITABLE"));
	}

	@Test
	void hidesClassroomFromOtherInstructorAndRejectsLearner() throws Exception {
		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.otherInstructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.material().getId(), 1)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("CLASSROOM_NOT_FOUND"));

		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.learner()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.material().getId(), 1)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
	}

	@Test
	void hidesMaterialLinkedOnlyToAnotherClassroom() throws Exception {
		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.foreignMaterial().getId(), 1)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("MATERIAL_NOT_FOUND"));
	}

	@Test
	void rejectsEmptyTextAndInvalidPlanTotalsOrDuplicates() throws Exception {
		LearningMaterial empty = addReadyMaterial(fixture.classroom(), 1, true);
		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(empty.getId(), 1)))
			.andExpect(status().isBadRequest());

		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.material().getId(), 21)))
			.andExpect(status().isBadRequest());

		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.material().getId(), 0)))
			.andExpect(status().isBadRequest());

		String duplicate = """
			{
			  "materialIds": [%d],
			  "questionPlan": [
			    {"questionType":"MCQ","count":1},
			    {"questionType":"MCQ","count":1}
			  ]
			}
			""".formatted(fixture.material().getId());
		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(duplicate))
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsAiPlanMismatchAndInvalidEssayRubric() throws Exception {
		when(aiClient.generateExamDraft(any()))
			.thenReturn(mcqResponse(fixture.exam().getId(), 2));
		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(singleMcqRequest(fixture.material().getId(), 1)))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("AI_RESPONSE_INVALID"));

		when(aiClient.generateExamDraft(any())).thenReturn(new ExamDraftResponse(
			"1.0",
			fixture.exam().getId(),
			List.of(new ExamDraftResponse.EssayQuestion(
				ExamQuestionType.ESSAY, 1, "essay-1", "Essay", BigDecimal.TEN,
				"Model", List.of(new ExamDraftResponse.Rubric(
					"Accuracy", new BigDecimal("0.8")
				))
			)),
			usage()
		));
		String essayRequest = """
			{"materialIds":[%d],"questionPlan":[{"questionType":"ESSAY","count":1}]}
			""".formatted(fixture.material().getId());
		mockMvc.perform(post(endpoint())
				.header(HttpHeaders.AUTHORIZATION, bearer(fixture.instructor()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(essayRequest))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("AI_RESPONSE_INVALID"));
	}

	private Fixture createFixture() {
		long suffix = SEQUENCE.incrementAndGet();
		User instructor = userRepository.save(User.create(
			"draft-instructor-" + suffix + "@example.com", "hash", "Instructor",
			UserRole.INSTRUCTOR
		));
		User other = userRepository.save(User.create(
			"draft-other-" + suffix + "@example.com", "hash", "Other",
			UserRole.INSTRUCTOR
		));
		User learner = userRepository.save(User.create(
			"draft-learner-" + suffix + "@example.com", "hash", "Learner",
			UserRole.LEARNER
		));
		Classroom classroom = classroomRepository.save(Classroom.create(
			instructor, "Draft classroom", LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15), ClassroomColor.BLUE, null,
			"DRAFT" + suffix
		));
		Classroom foreignClassroom = classroomRepository.save(Classroom.create(
			other, "Foreign classroom", LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15), ClassroomColor.GREEN, null,
			"FOREIGN" + suffix
		));
		LearningMaterial material = addReadyMaterial(classroom, 3, false);
		LearningMaterial foreignMaterial = addReadyMaterial(foreignClassroom, 1, false);
		Exam exam = examRepository.saveAndFlush(Exam.create(
			classroom, 1, "Draft exam", null, false
		));
		return new Fixture(
			instructor, other, learner, classroom, material, foreignMaterial, exam
		);
	}

	private LearningMaterial addReadyMaterial(
		Classroom classroom,
		int pageCount,
		boolean blank
	) {
		long suffix = SEQUENCE.incrementAndGet();
		ClassroomWeek week = weekRepository.save(ClassroomWeek.create(
			classroom,
			(int)(suffix % 100_000) + 1,
			"Week " + suffix,
			null,
			ClassroomWeekStatus.PUBLISHED,
			(int)suffix
		));
		LearningMaterial material = LearningMaterial.create(
			userRepository.getReferenceById(classroom.getInstructorId()),
			"Material " + suffix,
			"draft/material-" + suffix + ".pdf"
		);
		material.markReady(pageCount);
		material = materialRepository.save(material);
		weekMaterialRepository.save(ClassroomWeekMaterial.create(
			week, material, Instant.now()
		));
		for (int pageNumber = 1; pageNumber <= pageCount; pageNumber++) {
			pageRepository.save(MaterialPage.create(
				material,
				pageNumber,
				blank ? "   " : "Page text " + pageNumber
			));
		}
		pageRepository.flush();
		return material;
	}

	private ExamDraftResponse twoTypeResponse(Long examId) {
		return new ExamDraftResponse(
			"1.0",
			examId,
			List.of(
				new ExamDraftResponse.McqQuestion(
					ExamQuestionType.MCQ, 1, "mcq-1", "Question", BigDecimal.TEN,
					List.of(
						new ExamDraftResponse.Choice("a", "A"),
						new ExamDraftResponse.Choice("b", "B")
					),
					"a", "Because A"
				),
				new ExamDraftResponse.ShortQuestion(
					ExamQuestionType.SHORT, 2, "short-1", "Explain", BigDecimal.TEN,
					"Answer", List.of("Accuracy")
				)
			),
			usage()
		);
	}

	private ExamDraftResponse mcqResponse(Long examId, int count) {
		return new ExamDraftResponse(
			"1.0",
			examId,
			java.util.stream.IntStream.rangeClosed(1, count)
				.mapToObj(index -> (ExamDraftResponse.Question)
					new ExamDraftResponse.McqQuestion(
						ExamQuestionType.MCQ, 1, "mcq-" + index, "Question",
						BigDecimal.TEN,
						List.of(
							new ExamDraftResponse.Choice("a", "A"),
							new ExamDraftResponse.Choice("b", "B")
						),
						"a", "Because A"
					)
				).toList(),
			usage()
		);
	}

	private ExamDraftResponse.Usage usage() {
		return new ExamDraftResponse.Usage("grok-test", 10, 20, null);
	}

	private String endpoint() {
		return "/api/classrooms/%d/exams/%d/draft-questions".formatted(
			fixture.classroom().getId(), fixture.exam().getId()
		);
	}

	private String twoTypeRequest(Long materialId) {
		return """
			{
			  "materialIds": [%d],
			  "questionPlan": [
			    {"questionType":"MCQ","count":1},
			    {"questionType":"SHORT","count":1}
			  ]
			}
			""".formatted(materialId);
	}

	private String singleMcqRequest(Long materialId, int count) {
		return """
			{"materialIds":[%d],"questionPlan":[{"questionType":"MCQ","count":%d}]}
			""".formatted(materialId, count);
	}

	private String bearer(User user) {
		return "Bearer " + jwtTokenProvider.createAccessToken(user);
	}

	private record Fixture(
		User instructor,
		User otherInstructor,
		User learner,
		Classroom classroom,
		LearningMaterial material,
		LearningMaterial foreignMaterial,
		Exam exam
	) {
	}
}
