package io.edupilot.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.edupilot.ai.AiClient;
import io.edupilot.ai.AiClientException;
import io.edupilot.ai.AiFailureCategory;
import io.edupilot.classroom.Classroom;
import io.edupilot.classroom.ClassroomColor;
import io.edupilot.classroom.ClassroomMember;
import io.edupilot.classroom.ClassroomMemberRepository;
import io.edupilot.classroom.ClassroomRepository;
import io.edupilot.exam.dto.ExamAnswerRequest;
import io.edupilot.exam.dto.SubmitExamRequest;
import io.edupilot.global.error.ErrorCode;
import io.edupilot.user.User;
import io.edupilot.user.UserRepository;
import io.edupilot.user.UserRole;

@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.MOCK,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:exam-ai-invalid;MODE=MySQL;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"edupilot.cors.allowed-origins=http://localhost:5173",
		"edupilot.ai.base-url=http://localhost:8000",
		"edupilot.ai.internal-token=test-internal-token",
		"edupilot.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"edupilot.storage.root-directory=build/test-storage/exam-ai-invalid"
	}
)
@ActiveProfiles("jpa-context")
class ExamAiRequestInvalidJpaTest {

	@Autowired private UserRepository userRepository;
	@Autowired private ClassroomRepository classroomRepository;
	@Autowired private ClassroomMemberRepository memberRepository;
	@Autowired private ExamRepository examRepository;
	@Autowired private ExamQuestionRepository questionRepository;
	@Autowired private ExamSubmissionRepository submissionRepository;
	@Autowired private ExamAnswerRepository answerRepository;
	@Autowired private StudentExamService studentExamService;
	@MockitoBean private AiClient aiClient;

	@Test
	void recordsFailedSubmissionWhenAsyncRequestViolatesAiContract() throws Exception {
		User instructor = userRepository.save(User.create(
			"invalid-instructor@example.com", "hash", "Instructor", UserRole.INSTRUCTOR
		));
		User learner = userRepository.save(User.create(
			"invalid-learner@example.com", "hash", "Learner", UserRole.LEARNER
		));
		Classroom classroom = classroomRepository.save(Classroom.create(
			instructor,
			"Invalid request classroom",
			LocalDate.of(2026, 9, 1),
			LocalDate.of(2026, 12, 15),
			ClassroomColor.BLUE,
			null,
			"INVALIDAI"
		));
		memberRepository.save(ClassroomMember.create(
			classroom, learner, Instant.parse("2026-08-03T00:00:00Z")
		));
		Exam exam = Exam.create(classroom, 1, "Exam", null, false);
		exam.replaceTotalScore(new BigDecimal("10.00"));
		exam.publish(Instant.parse("2026-08-03T00:00:00Z"));
		exam = examRepository.save(exam);
		questionRepository.save(ExamQuestion.create(
			exam, 1, ExamQuestionType.SHORT, new BigDecimal("10.00"),
			new ExamPublicQuestion("Question", List.of()),
			new ExamPrivateAnswer(null, null, null, "Reference", null, List.of()),
			"1.0"
		));
		when(aiClient.grade(any())).thenThrow(new AiClientException(
			ErrorCode.AI_RESPONSE_INVALID,
			AiFailureCategory.SCHEMA,
			false,
			"AI_REQUEST_INVALID",
			null
		));
		Long examId = exam.getId();

		var submitted = studentExamService.submit(
			learner.getId(), UserRole.LEARNER, examId,
			new SubmitExamRequest(
				"invalid-contract",
				List.of(new ExamAnswerRequest("q1", "Answer"))
			)
		);
		assertThat(submitted.status()).isEqualTo(SubmissionStatus.SUBMITTED);

		ExamSubmission failed = awaitTerminal(examId, learner.getId());

		assertThat(failed.getStatus()).isEqualTo(SubmissionStatus.GRADING_FAILED);
		assertThat(submissionRepository.countByExam_Id(examId)).isEqualTo(1);
		assertThat(answerRepository.count()).isEqualTo(1);
	}

	private ExamSubmission awaitTerminal(Long examId, Long userId) throws Exception {
		for (int attempt = 0; attempt < 200; attempt++) {
			ExamSubmission submission = submissionRepository
				.findTopByExam_IdAndUser_IdOrderByAttemptNoDesc(examId, userId)
				.orElseThrow();
			if (submission.getStatus() != SubmissionStatus.SUBMITTED) {
				return submission;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("Async grading did not finish within 5 seconds");
	}
}
